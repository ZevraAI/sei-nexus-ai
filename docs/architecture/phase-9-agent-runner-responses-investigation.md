# Phase 9A — AgentRunner Responses API Investigation

Investigation only. No production code was modified to produce this report.

## 1. Executive Summary

`AgentRunner` runs a ReAct tool-calling loop entirely on OpenAI Chat Completions
(`AzureOpenAiClient.chatWithTools`, POSTing to `/chat/completions`), independent of every other
Agent Brain component already migrated to the Responses API (Stage 1, Planner, Evaluator,
Memory Selection, Composer). Its loop rebuilds and resends a pruned, in-process `List<AgentMessage>`
on every iteration — there is no `previous_response_id` usage, no Responses-API item model, and no
existing `AzureOpenAiClient` method that attaches function-type tools to a `/responses` request.

Two structural findings shape everything else in this report:

1. **`chatWithTools` only ever processes the first tool call in a model response**
   (`toolCalls.get(0)`, never iterated). If the model returns parallel tool calls, every call past
   index 0 is silently dropped — no execution, no result message, no error. A Responses API
   migration that faithfully mirrors current behavior must **not** start processing multiple
   `function_call` items just because the Responses API naturally supports it; doing so would be an
   unrequested behavior change smuggled into a "transport" migration.
2. **Whether an exception aborts the whole run depends entirely on exception type, not on which
   tool failed.** A `NexusException` thrown by a tool (or by `chatWithTools` itself) propagates
   uncaught through `AgentRunner.run`'s loop to its outer catch, marking the session `FAILED` and
   aborting the entire run. Any other exception thrown by a tool executor is caught inside
   `AgentToolRegistry.executeWithReference` and converted into a `{"error": ...}` JSON string fed
   back to the model as a normal, recoverable tool observation. This asymmetry is load-bearing
   today (e.g. `query_database`'s governance-rejection path deliberately returns an `"error"`
   string rather than throwing, specifically so the model can retry) and must be preserved exactly,
   not "cleaned up," by any migration.

The existing Responses API infrastructure in `AzureOpenAiClient` (`doRespond`, `respondWithStrictJson`,
`chatWithFileSearch`) provides transport primitives (retry/backoff, usage recording for the
Responses token-field shape, `previous_response_id` plumbing for File Search) but **zero** function/tool-calling
support: no method ever attaches a `type:"function"` tool, no parser ever reads a
`function_call` output item, and no code submits a `function_call_output` back. Every one of these
would be net-new work, not an extension of an existing seam.

Test coverage for AgentRunner's current behavior has real gaps precisely in the areas most likely to
move during a migration: iteration-limit/salvage handling, generic error-handling (`FAILED` status),
tool-result-cache-hit behavior, and history pruning at >6 pairs all have **no test coverage today**.
Migrating transport without first pinning this behavior with tests would make it impossible to prove
the migration preserved it.

**No implementation is proposed in this phase.** See §18 for the recommended migration boundary and
§20 for why Phase 9B's justification is conditional on writing the missing tests first.

## 2. Current AgentRunner Architecture

Full read: `src/main/java/com/sei/nexus/agentrunner/AgentRunner.java` (425 lines), a Spring
`@Service`.

**Public entry points:**
```java
public ZevraSession run(ZevraAgent agent, String inputMessage, String userEmail, String existingRunKey)
public ZevraSession run(ZevraAgent agent, String inputMessage, String userEmail, String existingRunKey,
                        String conversationContext, String conversationId, String parentExecutionId)
```

**Callers:**
- `ChatService.java:327` — the conversational/routed chat path (the 7-arg overload, with
  `conversationContext` built from the prior turn's `ExecutionReference` via
  `buildExecutionGrounding`, and `conversationId`/`parentExecutionId` threaded for lineage).
- `MorningBriefService.java:255` — autonomous brief generation (3-arg overload, `maxIterations`
  capped to 5).
- `ZevraAgentService.chat(...)` (via `ZevraAgentController.java:74-81`, `POST /{id}/chat`) — the
  direct single-shot agent-chat endpoint (3-arg overload, `existingRunKey=null`, **no
  conversationContext/conversationId/parentExecutionId at all** — zero cross-turn continuity on
  this path).

**Sequence (one full run):**

```
Caller (ChatService / MorningBriefService / ZevraAgentService)
  │
  ▼
AgentRunner.run(...)
  │  insert ZevraSession(status=RUNNING)
  │  resolve/create governance NexusRun
  │  AgentBrain.resolve(...) → ResolvedBusinessModel
  │  ExecutionContractBuilder → ExecutionContract
  │  PromptContext / grounding → buildSystemPrompt(...)
  │  toolRegistry.getToolDefinitions(agent.connectionKeys())
  │  messages = [AgentMessage.user(composeFirstMessage(conversationContext, inputMessage))]
  │
  ▼
while (iterations < agent.maxIterations()):
  │  iterations++
  │  LlmCallTag.set("AGENT_RUNNER")
  │  response = openAi.chatWithTools(pruneHistory(messages, 6), systemPrompt, tools)  ── HTTP → /chat/completions
  │
  ├─ answer = extractFinalAnswer(response)
  │    if answer != null:
  │      record FINAL_ANSWER step, completeSession(COMPLETED), return ZevraSession
  │
  ├─ else (tool call):
  │    cacheKey = toolName + ":" + argsJson
  │    if toolResultCache has cacheKey:
  │      result = cached; note "already made this exact call" appended
  │    else:
  │      result = toolRegistry.executeWithReference(toolName, args, agent.connectionKeys(),
  │                   userEmail, governanceRunKey, iterations, contract, conversationId, parentExecutionId)
  │      toolResultCache.put(cacheKey, result)
  │    record TOOL_CALL step
  │    messages.add(AgentMessage.assistantToolCall(toolCallId, toolName, argsJson))
  │    messages.add(AgentMessage.toolResult(toolCallId, llmContent))
  │  ── loop again ──
  │
  ▼ (loop exits without returning — iterations exhausted)
salvageAnswer(messages, systemPrompt, tools)
  │  prune history, append "do not call tools, give best answer now" user message
  │  LlmCallTag.set("AGENT_RUNNER_SALVAGE")
  │  response = openAi.chatWithTools(...)  ── still passes tools, tool_choice remains "auto"
  │  if response is a non-final-answer tool call → discarded, salvaged = null
  │  if any exception → caught locally, salvaged = null
  │
  ├─ salvaged != null → completeSession(COMPLETED, forcedAtMaxIterations=true)
  └─ salvaged == null → completeSession(MAX_ITER, canned "couldn't complete..." message)

(any Exception anywhere in the above, uncaught) → completeSession(FAILED) → rethrow NexusException
```

**State mutation per stage:** `messages` (in-process list, grows by 2 per tool iteration),
`toolResultCache` (in-process `LinkedHashMap`, one run's lifetime only), `ZevraSession` (persisted
once at start as `RUNNING`, once at end as terminal), `ExecutionReference` (persisted only inside
`GovernedSqlRuntime.execute`, for successful `query_database` calls), `ConversationMemoryService`
roster (mutated only via the optional memory tools, never by the core loop).

## 3. Current Tool-Calling Loop

**Multiple tools per response:** the model *may* request more than one (parallel tool calling is
on by default for Chat Completions unless `parallel_tool_calls:false` is set, which this request
body never sets). **Java executes at most one** — `AzureOpenAiClient.chatWithTools` reads only
`toolCalls.get(0)`, never iterating the array. Any call beyond index 0 is silently discarded: not
executed, no result message ever appended for it. Because OpenAI requires a `tool` role response
for every `tool_calls` entry a prior assistant message carried, and `AgentMessage.assistantToolCall`
is reconstructed from `AgentToolResponse`'s single captured call, this loop's design implicitly
assumes single-tool-call responses.

**Iterations:** governed by `agent.maxIterations()`, a per-`ZevraAgent`-configurable field
(`ZevraAgentController` defaults new agents to 10; `MorningBriefService` caps briefs to 5). There is
no separate hardcoded iteration constant in `AgentRunner` itself.

**What terminates the loop:**
- Model returns no tool call → `content` treated as final answer (even an empty string — see §13).
- Model calls the `final_answer` tool → `extractFinalAnswer` reads `args.get("answer")` directly
  from the parsed tool-call arguments, before the tool registry is ever invoked for that call (the
  registry's own `"final_answer"` switch case is effectively unreachable in the normal loop).
- Iteration budget exhausted → falls through to `salvageAnswer` (one more, budget-independent
  `chatWithTools` call with an added "don't call tools" instruction, `tools` still attached, still
  `tool_choice:"auto"`).
- Any exception (LLM call or tool dispatch, except inside `salvageAnswer`'s own local catch) →
  aborts the whole run as `FAILED`.

**Malformed tool arguments:** `objectMapper.readValue(argsJson, Map.class)` throwing (invalid JSON
in `function.arguments`) happens *inside* `chatWithTools`, before the registry is reached; the
enclosing catch converts it to a `NexusException("Failed to parse tool-call response: ...")`, which
propagates uncaught — **the whole run aborts**, it is not treated as a recoverable per-tool
observation.

**Unknown tool:** `AgentToolRegistry.executeWithReference`'s switch `default` throws
`NexusException(BAD_REQUEST, "Unknown tool: " + toolName)` — re-thrown uncaught (not converted to
an error string) — **the whole run aborts**. A disabled memory tool (feature-flagged off) produces
the identical "Unknown tool: ..." message via `requireEnabled`, indistinguishable from a genuinely
nonexistent tool.

## 4. Current Conversation State

- **Within one loop:** `messages` (in-process `List<AgentMessage>`) is the entire state; no
  `previous_response_id`/response-id reuse anywhere — confirmed zero references to
  `previous_response_id`/`previousResponseId` in `AgentRunner.java`. Every `chatWithTools` call
  resends the full pruned history.
- **Across separate HTTP turns:** `AgentRunner.run(...)` itself never resumes anything — it is
  always a fully self-contained, single-shot loop. Cross-turn continuity is a caller-side
  (`ChatService`) responsibility: the prior turn's `ExecutionReference` (looked up by
  `conversationId`) is turned into prose (`buildExecutionGrounding`) and prepended to the fresh
  loop's first message (`composeFirstMessage`). `ZevraSession` plays no role in this continuity —
  it is a write-once, terminal audit record of one already-finished run, never read back into a
  later `run()` call.
- **`ZevraAgentService.chat(...)`** (the direct `/chat` endpoint) has **zero** cross-turn
  continuity of any kind — no `conversationContext`, no `conversationId`, no prior-turn lookup.
- **Conversation Memory** (`ConversationMemoryService`, roster of discovered Business Objects,
  keyed by `conversationId`) is reached only through the optional memory tools the LLM may choose
  to call — never by the core loop's own message-building/pruning/iteration-control logic.

## 5. Current History / Pruning

`pruneHistory(messages, HISTORY_KEEP_PAIRS=6)` (`AgentRunner.java:375-391`): always keeps
`messages[0]` (the original composed user question) plus only the last 6 tool-call/tool-result
pairs, called before every `chatWithTools` invocation (both in the main loop and in
`salvageAnswer`). This exists purely to bound prompt growth across many tool iterations within one
run — it has nothing to do with Chat Completions' message-array format specifically, other than
that pairs are counted as (assistant tool_call, tool result) message pairs, a shape Chat Completions
happens to use. **Whether Responses API eliminates the need for pruning:** no — pruning bounds
*token growth from repeated tool iterations*, a concern that exists identically whether the
transport is `messages` resend or `previous_response_id` continuation (a long `previous_response_id`
chain still accumulates server-side context and, more importantly, Zevra's own reasoning
provenance/audit trail would still need bounding for whatever Java-side representation survives).
This is a genuine "requires a design decision" item, not a free win from switching transport (see
§8/§17).

## 6. Current Tool Result Caching

`toolResultCache`: an in-process `LinkedHashMap<String, AgentToolRegistry.ToolExecutionResult>`,
one instance per `run()` invocation (not persisted, not shared across runs). Cache key: exact
string `toolName + ":" + argsJson`. On a hit, the cached `ToolExecutionResult` is reused (no
re-execution) and the message fed back to the model is annotated with a "you already made this
exact call" note. This exists specifically because history pruning (§5) can drop an earlier tool
result out of the model's visible context, and the model may then repeat an identical call — the
cache prevents redundant DB execution and gives the model a pointed hint instead of a fresh
(possibly different, e.g. for non-deterministic tools) result. **Does not depend on Chat
Completions message format** — it's keyed on tool name + arguments JSON, which is a
transport-agnostic concept; a Responses API migration would carry this over unchanged as long as
`toolName`/`argsJson` are still extractable from whatever item shape replaces `AgentToolResponse`.

## 7. Current OpenAI API Contract

`AzureOpenAiClient.chatWithTools` (`AzureOpenAiClient.java:1112-1176`), POSTing to
`{BASE_URL}/chat/completions`:

- `messages`: a `system` entry (if `systemPrompt` non-blank) + one entry per `AgentMessage` —
  `role` copied verbatim; `tool_calls` set (no `content` key) when the message carries them,
  otherwise `content` set; `tool_call_id` added whenever the message carries one (tool-result
  messages).
- `tools`: passed straight through from `AgentToolRegistry.getToolDefinitions(...)` — each entry
  `{"type":"function","function":{"name","description","parameters"}}`.
- `tool_choice`: hardcoded `"auto"` — never forced to a specific tool or `"none"`.
- `model`: `chatModel` field (`@Value("${nexus.openai.chat-model:gpt-4o}")`) — **no per-agent model
  override exists anywhere in this path**; every `AgentRunner` run for every tenant/agent uses the
  same global `gpt-4o` (unless the config default is changed globally).
- Fixed `temperature=0.2`, `max_tokens=4096`. No `response_format`/structured-output field at all.
- Response parsing reads `choices[0].message` → `tool_calls[0]` (single call only, §3) or
  `content` (final answer).
- Retry/transport: shares the exact same `executeWithRetry`/`throttledSendWithRetry`/
  `sendWithRetryLocked` infrastructure as every other client method (429 backoff, generic
  exponential retry, global concurrency semaphore) — no bespoke retry logic for tool calling.
- Usage: `recordUsage` reads Chat Completions' field names —
  `usage.prompt_tokens`/`completion_tokens`/`prompt_tokens_details.cached_tokens`.

## 8. Responses API Mapping

| Current AgentRunner (Chat Completions) | Responses API equivalent | Status |
|---|---|---|
| `system` message | `instructions` field | Exists today (`doRespond`/`respondWithStrictJson` already use `instructions`) |
| `user` message | an `input` item (or the simple `input` string form) | Exists today for non-tool calls; multi-item `input` array (needed once tool turns exist) is **not built anywhere today** |
| `assistant` message (tool call) | a `function_call` output item, later replayed back as an `input` item on the next call | **Absent** — no code builds or parses this shape |
| `assistant` message (final text) | a `message`-type output item, `output_text` content | Exists today (`extractResponseText`) |
| tool/function definition | `{"type":"function","name","description","parameters"}` at the request's top-level `tools` array (Responses API's function-tool shape differs slightly from Chat Completions' nested `function:{...}` wrapper) | **Absent** — only `{"type":"file_search",...}` is ever built today |
| tool call (`tool_calls[]` entry) | a `function_call` item in `output[]`, carrying `call_id`, `name`, `arguments` | **Absent** — `extractResponseText` explicitly skips every non-`"message"` item; no code reads `function_call` |
| tool arguments (`function.arguments` string) | `function_call.arguments` (also a JSON string) | Same string-JSON shape; only the read path is missing |
| tool result (`role:"tool"` message + `tool_call_id`) | a `function_call_output` input item keyed by `call_id` | **Absent** — no code submits this shape |
| assistant final answer | `output[].type=="message"` → `content[].type=="output_text"` | Exists today |
| conversation history (`messages` array resend) | either full `input` array replay (a direct analog) or `previous_response_id` chaining (a different mechanism) | Full-replay analog is straightforward; chaining requires a design decision (§11) |
| `previous_response_id` | same field name | Plumbing exists (`chatWithFileSearch` only) but not generalized; would need extending to a tool-calling method |
| `usage` (`prompt_tokens`/`completion_tokens`/cached) | `usage.input_tokens`/`output_tokens`/`input_tokens_details.cached_tokens` | Parser already exists (`recordResponsesUsage`) but is not wired to any tool-calling call site (none exists yet) |
| `tool_choice: "auto"` | same field, same semantics per Responses API | Not yet used anywhere, but a direct value carry-over |
| response status (`completed`/`incomplete`/`failed`) | top-level `status` + `incomplete_details` | **No existing code path anywhere reads this** — flagged as a **genuine design decision**: today's Chat Completions path has no equivalent check at all (completion is inferred purely from `tool_calls` being empty), so a migration must decide whether to start checking `status` (arguably safer) or preserve the current absence of such a check (arguably more faithful to "no behavior change") |

## 9. Tool Call / Tool Output Mapping

Responses API item types relevant to a faithful migration, cross-referenced against what
`AgentRunner`'s current state needs to represent:

- **`message`** (output) — already handled (`extractResponseText`). Needed for: final answer text.
- **`function_call`** (output) — **not handled anywhere in the codebase.** Needed for: detecting
  and reading a tool call (`call_id`, `name`, `arguments`) — the direct Responses-API analog of
  `choices[0].message.tool_calls[]`.
- **`function_call_output`** (input, submitted by the caller on the next turn) — **not handled
  anywhere.** Needed for: submitting a tool's result back, replacing the current
  `role:"tool"`/`tool_call_id` message shape.
- No other Responses API item type is currently relevant to AgentRunner's own state — File
  Search's `file_search_call` item type is specific to Stage 1's separate code path and is not used
  by (nor would be needed by) AgentRunner's tool loop, since AgentRunner does not use File Search.

Mapping onto current state: `AgentToolResponse.ofToolCall(toolName, toolCallId, args)` would become
"the `function_call` item's `name`/`call_id`/parsed `arguments`"; `AgentMessage.assistantToolCall`/
`toolResult` would become "append a `function_call` item to the replay buffer" / "append a
`function_call_output` item keyed by the same `call_id`" — a structurally different mechanism
(flat item array with `call_id` linking, vs. role-tagged messages with `tool_call_id` linking), not
a drop-in rename.

## 10. Multi-Tool Behavior

Confirmed (§3, §7): **current behavior processes only the first tool call in a response, silently
dropping the rest.** The Responses API naturally represents multiple `function_call` items in one
`output[]` array — nothing prevents the model from returning several. A migration must explicitly
decide how to preserve "process only the first" semantics: e.g., reading only `output[0]` of type
`function_call`, or the first `function_call`-typed item encountered, and — critically — deciding
what to do with the *other* `function_call` items still present in the same `output[]`, since
Responses API's turn-taking contract may require a `function_call_output` for every `function_call`
before the next turn can proceed (unlike Chat Completions, where the mismatch is simply latent
until it causes a downstream request-shape error). This is exactly the kind of behavior interaction
that must be resolved by an explicit design decision, not by "whatever falls out" of adapting the
existing single-call-reading code to the new item shape — silently starting to process every
`function_call` would be an un-requested behavior change (§6 of the task's constraints:
"DO NOT silently improve this behavior during migration").

## 11. previous_response_id Analysis

**Current state:** zero usage in `AgentRunner`. Every iteration resends the full (pruned)
`messages` array. `previous_response_id` plumbing exists today only in `chatWithFileSearch`
(private 6-arg overload) for Stage 1's cross-turn concept-resolution chaining — a wholly separate
code path.

**Two distinct chaining questions, not one:**
1. *Within one loop's iterations* (tool-call round-trips inside a single `AgentRunner.run()`
   call) — using `previous_response_id` here would mean each iteration sends only the new
   `function_call_output` (plus any new instruction text) rather than the whole pruned history,
   with OpenAI reconstructing prior turns server-side.
2. *Across separate HTTP turns* (`ChatService`'s conversational continuity) — today implemented via
   prose-grounding from `ExecutionReference`, not response-id chaining at all; a separate,
   independently migratable concern (§4).

For (1), using `previous_response_id` **would plausibly reduce input tokens** re-sent per
iteration (no evidence/measurement performed in this pass — flagging as a plausible, not proven,
effect) and would reduce the Java-side burden of rebuilding `messages` each turn. It would **not**
eliminate the need for Zevra's own audit trail (`ZevraSession.stepsJson`, `ExecutionReference`
persistence) since those are Zevra-authoritative records, not derivable from OpenAI's response
history. It would introduce a **new failure mode**: if a `response_id` expires or becomes
unavailable server-side (OpenAI does not guarantee indefinite retention), the loop would need a
recovery path — today's full-history-resend model has no equivalent dependency on OpenAI-side
state surviving between calls. No such recovery design exists today and none is proposed here.

**Explicit non-conclusion, per the task's own instruction:** this investigation does not conclude
`previous_response_id` *should* replace `AgentRunner`'s explicit history. It is architecturally
usable as a transport optimization (Zevra's own state — `ZevraSession`, `ExecutionReference`,
`ConversationMemoryService` — would remain the authoritative record regardless of which transport
carries the model's own turn-taking state), but adopting it trades a currently-simple,
fully-Zevra-controlled mechanism (resend everything, always reproducible) for a dependency on
OpenAI-side state availability, without a demonstrated need.

## 12. Zevra State vs OpenAI State

The current architecture already draws this boundary correctly and would continue to do so under
any of the options in §16: `TenantContext`/tenant identity, `ConversationMemoryService` roster,
`ExecutionReference`, `ZevraSession`, `ExecutionContract`, tool authorization (`allowedConnections`
checks inside `AgentToolRegistry`), and audit (`stepsJson`) are all Java/Postgres-owned today and
have no OpenAI-side counterpart — none of them would need to move to "OpenAI state" under a
Responses API migration, and nothing in the investigated code suggests any pressure to move them.
`previous_response_id` (if adopted per §11) would only ever carry *model conversation
continuity*/*transport-level turn replay* — never business state, tool authorization, or persistence
— consistent with how it is already used (narrowly) in Stage 1 today.

## 13. Failure Semantics

| Failure | Current behavior | Responses API mapping consideration |
|---|---|---|
| HTTP failure/timeout | `executeWithRetry`'s shared retry/backoff (4 retries, exponential); exhausted → `NexusException`, uncaught → run `FAILED` | Same shared infrastructure would apply to any new Responses-API tool-calling method — no bespoke retry needed, none should be added |
| Rate limit (429) | Same shared retry path, `Retry-After`/`x-ratelimit-reset-requests`-aware backoff | Same — transport-level, unaffected by API choice |
| Malformed response body | `choices[0].message` access is null-safe (Jackson `MissingNode`); degrades to empty final answer unless `readTree` itself throws (then `FAILED`) | Responses API's `output[]` walk is equally null-safe by construction (`for (JsonNode item : root.path("output"))` over a missing node iterates zero times) — an equivalent "no message/function_call found" degrades similarly, but see the `status`/`incomplete_details` gap (§8) — no existing code distinguishes a genuinely empty response from an `incomplete` one |
| Malformed tool arguments | `objectMapper.readValue` throws → uncaught `NexusException` → whole run `FAILED` | Same shape possible for `function_call.arguments`; whether this stays a full-run-aborting failure or becomes a recoverable tool-observation is a **preserve-current-behavior** requirement, not a design choice |
| Unknown/unregistered tool | `NexusException(BAD_REQUEST, "Unknown tool: ...")`, uncaught → whole run `FAILED` | Same registry method (`executeWithReference`) would still be the dispatch point regardless of transport — behavior unaffected by the API migration itself |
| Tool execution failure — `NexusException` | Uncaught → whole run `FAILED` | Unaffected by transport choice — this is a registry/tool-executor behavior, not an API-shape behavior |
| Tool execution failure — other `Exception` | Caught by registry, converted to `{"error":...}` fed back to model as a recoverable observation | Same — registry-level behavior, transport-independent |
| Empty/refused response (no content, no tool call) | Treated as a valid final answer with `answer=""` — silent "success" | Same silent-empty-success risk would carry over unless the migration explicitly decides to start checking `status`/`incomplete_details` — which would be a **behavior change**, requiring explicit sign-off, not an incidental side effect of the transport swap |
| Iteration limit reached | `salvageAnswer` — one extra call, tools still attached, `tool_choice` still `"auto"`; a non-final-answer tool call from salvage is discarded; any exception locally caught → `salvaged=null` → `MAX_ITER` | Same logic, same call shape, would carry over verbatim — this is `AgentRunner`'s own control flow, not API-shape-dependent |
| Model refusal | Not specially handled today — Chat Completions refusals surface as ordinary `content` text (no dedicated refusal field read) | Responses API has its own refusal representation in some cases; **not currently read by any code** — parity would mean continuing not to special-case it, unless explicitly decided otherwise |
| Partial/incomplete response | **No detection exists today** (§8) | Must be an explicit decision: preserve the absence of detection (faithful migration) or add it (a behavior change requiring its own justification, outside "transport migration" scope) |

**Hard constraint reaffirmed:** no Chat Completions fallback, no semantic retry, no alternate tool
selection, no Java repair, no automatic alternate model — none of the failure paths above call for
any of these, and none should be introduced by a migration.

## 14. Usage / Telemetry

Current: `chatWithTools` → `recordUsage` (Chat Completions shape: `prompt_tokens`/
`completion_tokens`/`prompt_tokens_details.cached_tokens`), tagged `"AGENT_RUNNER"` (main loop) or
`"AGENT_RUNNER_SALVAGE"` (salvage call) via `LlmCallTag`; the nested vision tool
(`analyze_image`) separately tags `"AGENT_TOOL_IMAGE_ANALYSIS"` around its own `analyzeImage` call.

A Responses-API tool-calling method would need to call `recordResponsesUsage` (already exists,
already parses the correct Responses-shape field names — `input_tokens`/`output_tokens`/
`input_tokens_details.cached_tokens`) instead of `recordUsage`, and would need to preserve the same
`LlmCallTag` values (`AGENT_RUNNER`/`AGENT_RUNNER_SALVAGE`/`AGENT_TOOL_IMAGE_ANALYSIS`) so
existing dashboards/queries against `nexus_usage_event.call_type` continue to work unchanged. No
telemetry field would need to disappear; the only change is which parser (`recordUsage` vs.
`recordResponsesUsage`) reads which JSON shape — a mechanical substitution, not a schema change.

## 15. Test Coverage

| Category | Coverage | Notes |
|---|---|---|
| A. Model request construction | Partial | `AzurePayloadCaptureTest` asserts exact Chat Completions JSON shape (capture-and-abort); `PromptHardeningLiveProbe` is live/opt-in |
| B. Tool selection | Indirect only | Tool *choice* is entirely the LLM's; tests only verify dispatch of whatever a script returns |
| C. Tool execution | Good | `AgentToolRegistryTest`, `AgentToolRegistryMemoryCapabilitiesTest` |
| D. Tool result handling | Good | `withExecutionIdNote`, result-shape tests in the registry test files |
| E. Multi-turn | Partial | Within-one-loop multi-iteration is covered (`AgentReadOnlyFlowIntegrationTest`); cross-turn continuity through `ZevraSession`/real HTTP boundaries is not exercised end-to-end |
| F. Iteration limits | **None** | No test references `MAX_ITER` or `salvageAnswer` |
| G. Error handling | **None** | No test drives the `FAILED`-status/outer-catch path |
| H. Caching | Gap | `toolResultCache`'s exact-repeat-call hit path (and its "already made this call" note) has no direct test despite the mechanism existing |
| I. History pruning | **None** | `pruneHistory`'s >6-pair truncation behavior is never exercised |
| J. Final answer | Good (indirect) | Exercised via integration test; `extractFinalSemantics`'s optional-field extraction has no direct unit test |
| K. Tenant/security | Nominal only | One "test" is a documentation-only `assertTrue(true)`; no real cross-tenant denial test at the `AgentRunner`/`ZevraAgentService` layer |

These gaps matter specifically because iteration-limit/salvage handling, error-path behavior,
caching, and pruning are exactly the mechanics most likely to shift shape (item-array vs.
message-array, call-id vs. tool-call-id linking, response-id-dependent vs. self-contained retries)
during a transport migration — they are currently unpinned by any test that would catch a
regression.

## 16. Migration Options

Presented without ranking, per instructions.

**Option A — Minimal transport migration** (swap `/chat/completions` for `/responses`, preserve
message-array-equivalent full-history replay each iteration, no `previous_response_id`).
- Classes affected: a new `AzureOpenAiClient` method (e.g. alongside `chatWithTools`), a new
  response/parsing path for `function_call` items, `AgentMessage`/`AgentToolResponse` either
  reused with new factory methods or replaced by Responses-item-shaped equivalents.
- State changes: none beyond transport — `messages` becomes an `input` array built the same way
  each iteration.
- Tool-loop changes: none, other than the single-call-only semantics needing explicit
  preservation (§10).
- Test impact: existing capture-style tests (`AzurePayloadCaptureTest`) would need a
  Responses-API-shaped counterpart; the untested areas (§15) would need new tests regardless of
  which option is chosen, to prove no regression.
- Failure behavior: closest to 1:1 preservation of current behavior, since no chaining
  dependency is introduced.
- Observability impact: swap `recordUsage` → `recordResponsesUsage` at the new call site;
  `LlmCallTag` values unchanged.
- Multi-turn impact: none — cross-turn continuity (`ChatService`'s `ExecutionReference`
  grounding) is untouched, since it doesn't depend on which transport `AgentRunner` uses
  internally.
- Tenant/security impact: none — `AgentToolRegistry`'s scoping checks are transport-independent.
- Architectural risks: the new `function_call`/`function_call_output` parsing/construction code
  is entirely net-new and must faithfully replicate the single-call-only and
  exception-type-dependent-abort behaviors (§3, §13) rather than "naturally" adopting whatever the
  Responses API makes easiest.

**Option B — Responses API + `previous_response_id` for the internal tool loop.**
- Classes affected: same as Option A, plus `AgentRunner`'s loop would need to track and pass a
  response id between iterations instead of rebuilding `messages`/`input` from scratch.
- State changes: introduces a new per-run piece of state (the latest response id) that must
  survive between iterations; `ZevraSession`/`ExecutionReference`/audit trail remain unaffected
  (Zevra-authoritative, independent of this id).
- Tool-loop changes: each iteration sends only the new `function_call_output` (plus, on the first
  iteration, the initial input) rather than the full pruned history — pruning's *purpose* (bound
  what the model sees) would need re-examination, since the model's context is now server-managed
  across the chain (§5's "not a free win" finding).
- Test impact: capture-style tests would need to assert the *chain* (relationship between
  consecutive request/response ids), not just one request's shape — a materially different test
  design.
- Failure behavior: introduces a new failure mode absent today — an expired/unavailable
  `response_id` mid-loop, with no existing recovery design.
- Observability impact: same as Option A, plus tracking response ids in logs (e.g. via
  `OperationCorrelationId`, following Stage 1's own precedent) would likely be needed for
  debugging chain breaks.
- Multi-turn impact: purely internal to one `run()` call — does not change how `ChatService`
  achieves cross-turn continuity (a separate, currently prose-based mechanism, §4).
- Tenant/security impact: none directly, but a `response_id`-chain failure recovery design would
  need to confirm it never leaks state across tenants/runs (not evaluated in this investigation,
  since no such design exists to evaluate).
- Architectural risks: adds a genuine, currently-nonexistent dependency on OpenAI-side state
  availability for an internal Zevra loop that is otherwise fully self-contained and
  reproducible today.

**Option C — Responses API while retaining Zevra's existing explicit history representation** (no
`previous_response_id`, full `input` array resent each iteration, but reshaped from
`AgentMessage`/Chat-Completions-message-array into Responses-API `input`/`output` item shapes).
- This is effectively Option A's "no chaining" stance stated as its own option — classes
  affected, test impact, and risk profile are the same as Option A. Distinguished from Option A
  only in framing: explicitly a decision to keep Zevra's own message/history model as the
  source of truth for what gets resent, translating it into Responses-API item shape at the
  transport boundary only, rather than treating the transport migration and the "should Zevra
  keep its own explicit history" question as open at the same time.

**Option D — Responses API with a new internal adapter translating between `AgentMessage` and
Responses items.**
- Classes affected: a new adapter class/layer (e.g. converting `List<AgentMessage>` ↔ Responses
  API `input`/`output` item lists) sitting between `AgentRunner` and `AzureOpenAiClient`, so
  `AgentRunner`'s own code continues operating on `AgentMessage`/`AgentToolResponse` largely
  unchanged, with the adapter absorbing all Responses-API-shape concerns.
- State changes: none beyond the adapter's own translation logic — `AgentRunner`'s control flow
  (loop, pruning, caching) stays literally unchanged; only the object passed to/from
  `AzureOpenAiClient` differs.
- Tool-loop changes: none to `AgentRunner` itself; the adapter is responsible for correctly
  representing "process only the first tool call" at the translation boundary.
- Test impact: existing `AgentRunner`-level tests (scripted `AzureOpenAiClient` subclasses
  overriding `chatWithTools`) would need an equivalent scripted override of whatever new method the
  adapter calls — a mechanical update, not a redesign, IF the adapter's new method has an
  equivalent single-call return shape to today's `AgentToolResponse`.
- Failure behavior: same considerations as Option A, isolated inside the adapter.
- Observability impact: same as Option A.
- Multi-turn impact: none.
- Tenant/security impact: none.
- Architectural risks: introduces a new abstraction layer whose sole justification would need to
  be minimizing churn in `AgentRunner.java` itself — worth weighing against Option A/C's simpler
  "just change the one method's transport" shape, since the adapter itself is new code that must
  also be tested and could itself introduce subtle mismatches (e.g. silently changing how
  single-vs-multi-tool-call is represented) if not built carefully.

**Option E — Do not migrate AgentRunner in this phase.**
- No classes affected, no risk introduced. Explicitly available given §15's test-coverage gaps —
  migrating a loop whose iteration-limit, error, caching, and pruning behavior are currently
  unpinned by any test makes it structurally hard to prove "no behavior change," which is the
  investigation's own stated objective.

## 17. Behavioral Risks

| Area | Current behavior | Responses API difference | Migration risk |
|---|---|---|---|
| Tool calls | Read from `choices[0].message.tool_calls[0]` | Represented as `function_call` items in `output[]`, potentially multiple | New parsing code must deliberately read only the first, discarding others exactly as today, or the migration silently changes semantics |
| Multiple tool calls | Silently drops all but the first, no error, no result message for dropped calls | Responses API may require a `function_call_output` for every `function_call` before the next turn | If the new code processes only one call but the API expects outputs for all, the next request could be rejected/malformed in a way today's Chat Completions loop never encounters — a genuinely new failure surface |
| Tool outputs | `role:"tool"` message + `tool_call_id` | `function_call_output` input item + `call_id` | Structurally different linking mechanism; any bug here manifests as the model losing track of which result belongs to which call |
| History | Full `messages` array resent every iteration, in-process, always reproducible from Java state | Either full `input` replay (same reproducibility) or `previous_response_id` chaining (dependent on OpenAI-side retention) | Choosing chaining introduces a failure mode with no existing recovery design |
| previous_response_id | Not used by AgentRunner at all | Available, but only demonstrated (elsewhere) for a much simpler single-call-per-turn Stage 1 flow, never for a multi-iteration tool loop | Untested territory — no precedent in this codebase for chaining across tool-execution turns specifically |
| Pruning | Caps at last 6 tool-call/result pairs, Java-side, fully under Zevra's control | If chaining is adopted, "what the model sees" is server-managed; pruning's role would need redefinition, not a straightforward carry-over | Risk of either double-bounding (redundant) or losing the guarantee entirely if not redesigned deliberately |
| Caching | Keyed on `toolName:argsJson`, transport-agnostic | No difference in principle | Low risk, but the *representation* fed to the model on a cache hit (the "already called this" note) must still map cleanly onto whatever item shape replaces `AgentMessage.toolResult` |
| Iteration limit | `agent.maxIterations()`, Java-side while-loop, no API dependency | No difference — this is Zevra's own control flow | Low risk in isolation, but **currently untested** (§15), so a regression here would not be caught by any existing test |
| Errors | `NexusException` aborts run; other `Exception` becomes a recoverable tool observation — an asymmetry baked into today's design | Same registry-level distinction is transport-independent | Risk is not in the mapping itself, but in the **currently-zero test coverage** of the abort path (§15) — a migration could not prove this is preserved without first writing a test for it |
| Final answer | Empty string treated as a valid, silent "success" | No existing `status`/`incomplete_details` check anywhere to distinguish empty-content from a genuinely incomplete response | A migration that starts checking `status` would change this silently-successful-empty-answer behavior — must be an explicit, separately-justified decision, not an incidental one |
| Usage | `recordUsage` (Chat Completions field names) | `recordResponsesUsage` already exists and parses the correct shape | Low risk — a mechanical parser swap, already proven correct for other Responses-API call sites |
| Tenant isolation | Enforced by `AgentToolRegistry`'s `allowedConnections` check + ambient `TenantContext` routing, transport-independent | No difference | Low risk — nothing about tenant scoping depends on which OpenAI API is used |
| Security | Same as tenant isolation — governance/`GovernedSqlRuntime` gating is entirely transport-independent | No difference | Low risk |
| Persistence | `ZevraSession`/`ExecutionReference` writes are Zevra-owned, triggered by Java control flow, not by API shape | No difference | Low risk |

## 18. Recommended Migration Boundary

If Phase 9B proceeds, the smallest boundary consistent with the evidence above is **Option A/C**
(minimal transport migration, explicit full-history replay each iteration, no
`previous_response_id` chaining for the internal tool loop): it requires no new dependency on
OpenAI-side state retention, preserves every currently-observed behavior (including the
single-tool-call-only and exception-type-dependent-abort asymmetries) with the most direct,
verifiable mapping, and defers the `previous_response_id` question (Option B) to a later,
separately-justified phase once the transport-only migration itself is proven safe by tests.

This is a conditional recommendation on process, not a design preference: per §15/§20, the
prerequisite tests (iteration-limit/salvage, error/`FAILED` path, cache-hit behavior, pruning at
>6 pairs) do not exist today for the *current* Chat Completions implementation. Writing them first
— against the current, unmigrated code — is what would make "no behavior change" provable at all,
regardless of which migration option is eventually chosen.

## 19. Required Regression Tests

Needed regardless of migration option, to first pin current behavior (before any code change) and
then confirm parity (after):

1. Single tool call → executed, result fed back, loop continues (already covered).
2. Multiple tool calls in one model response → only the first is executed; the rest produce no
   result message and no error (**currently untested** — must be pinned before migrating).
3. Iteration limit reached, salvage call returns a final answer → `COMPLETED` with
   `forcedAtMaxIterations=true` (**currently untested**).
4. Iteration limit reached, salvage call itself returns a non-`final_answer` tool call → discarded,
   `MAX_ITER` (**currently untested**).
5. Iteration limit reached, salvage call throws → caught locally, `MAX_ITER` (**currently
   untested**).
6. A tool throws `NexusException` → whole run `FAILED`, no partial completion (**currently
   untested**).
7. A tool throws a non-`NexusException` → converted to `{"error":...}`, run continues
   (**currently untested** as an explicit assertion, though implied by governance-rejection tests).
8. Unknown tool name requested → `FAILED` (**currently untested**).
9. Malformed tool-call arguments (invalid JSON) → `FAILED` (**currently untested**).
10. Exact-repeat tool call (same name + args) within one run → served from `toolResultCache`, not
    re-executed, "already made this call" note present (**currently untested**).
11. History exceeding 6 tool-call/result pairs → correctly pruned, `messages[0]` always retained
    (**currently untested**).
12. Empty/no-content, no-tool-call model response → treated as a valid empty final answer
    (**currently untested** as an explicit assertion).
13. Tenant A's agent cannot access Tenant B's connections/tool results (**currently only nominal
    coverage — needs a real assertion**).
14. Cross-turn continuity via `ChatService`'s `ExecutionReference` grounding still produces the
    expected first-message composition (`composeFirstMessage`) — already covered.
15. (Post-migration only) Responses-API request payload shape assertions equivalent to
    `AzurePayloadCaptureTest`'s current Chat Completions assertions.
16. (Post-migration only, only if Option B is later pursued) Chain continuity across at least 3
    tool iterations using `previous_response_id`, plus an explicit test for the
    expired/unavailable-response-id recovery path.

## 20. Phase 9B Implementation Plan

**Not authorized by this investigation as-is.** The evidence supports a narrow, low-risk transport
migration (Option A/C) as architecturally sound and free of the deeper risks in Option B/D, but
also shows that AgentRunner's current behavior in exactly the areas a migration would touch
(iteration limits, error aborts, caching, pruning) is not pinned by any existing test. Per this
phase's own success criteria ("what tests are required before and after migration"), Phase 9B
should not begin implementation until §19's "currently untested" items are written against
**today's Chat Completions implementation** first — establishing a verifiable baseline — after
which a Phase 9B could implement Option A/C and re-run the same tests (adapted only where the
transport-facing assertions themselves must change, e.g. payload-shape capture tests) to
demonstrate parity.

If a future Phase 9B is chartered, its scope per this investigation's evidence should be: add the
missing tests against current behavior; then implement Option A/C only (no `previous_response_id`
chaining, no multi-tool-call processing, no `status`/`incomplete_details` handling beyond what
exists today); do not change models, prompts, tool schemas, or the salvage/pruning/caching logic
itself. Options B and D are documented here as evaluated-but-not-selected — not because they were
ranked lower, but because they each introduce either a new state-availability dependency (B) or a
new abstraction layer (D) that the evidence does not show to be currently necessary.
