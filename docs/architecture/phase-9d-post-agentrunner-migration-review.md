# Phase 9D — Post-AgentRunner Responses Migration Review

> **Note (2026-10-18).** Stage 1 no longer uses File Search; it was replaced by retrieval from a pgvector concept index, and the single Stage 1 row in the table below reflects that. Prose elsewhere in this review that mentions File Search describes the position at the time it was written.

Investigation only. No production or test code was modified to produce this report.

## 1. Executive Summary

**FACT:** After Phases 1–4 (core Agent Brain: Stage 1/Planner/Evaluator/Composer/Memory
Selection) and Phase 9C (AgentRunner), the Responses API is now the transport for every
*conversational* LLM call in the system. Chat Completions survives only for: (a) the now-orphaned
`chatWithTools` method itself (zero production callers), (b) a genuinely-still-reachable legacy
Decision Router fallback, (c) a family of non-conversational one-shot/background tasks (routing
classification, onboarding/discovery/pack analysis, embeddings, vision, term/correction learning,
teaching, automation generation) that were never part of any Responses migration scope and show no
evidence of needing one.

**FACT:** `getLlmDecision()` is not dead code. It fires whenever Stage 1's combined
concept+routing call does not produce a routing decision — which happens for any tenant/connection
that has not yet had an Industry Pack applied with a populated concept catalog, or when the model's
own `routing` field is malformed. This is proven by dedicated tests
(`ChatServiceDecisionRouterAbsorptionCallCountTest`) asserting both the zero-call and exactly-one-call
cases.

**FACT:** `AzureOpenAiClient.chatWithTools` has zero production call sites after Phase 9C — only
`respondWithTools` is invoked by `AgentRunner`. It is retained code, exercised only by tests.

**INFERENCE:** The codebase does not currently show enough duplicated LLM-orchestration logic
(model selection, retry, telemetry, structured-output wiring) to justify a centralized "execution
policy layer" — each `respondFor*`/`chatWith*` method is a thin, distinct wrapper sharing the same
two or three underlying transport primitives (`executeWithRetry`, `recordUsage`/
`recordResponsesUsage`) already, not a proliferation of divergent implementations.

**RECOMMENDATION:** See §15. No implementation is proposed in this phase.

## 2. Current LLM Call Inventory

| Caller | Method | API | Model | Tag | Interactive/Background | Conversational | Tool Calling | Status |
|---|---|---|---|---|---|---|---|---|
| `ConceptScopedMetadataResolver.selectConceptsAndRouting` | `respondForStage1` | Responses, strict schema, no tools (candidates retrieved from the pgvector concept index) | `stage1Model` (gpt-4.1) | `STAGE1_CONCEPT_AND_ROUTING` | Interactive, sync | Yes (Chat and agent paths) | No | Live — single Stage 1 path for both callers |
| `ReasoningPlanner.nextStep` | `respondForPlanner` | Responses, strict schema | `plannerModel` (gpt-4.1) | `PLANNER` | Interactive, sync | Yes | No | Live |
| `ReasoningEvaluator.evaluate` | `respondForEvaluator` | Responses, strict schema | `evaluatorModel` (gpt-4.1) | `EVALUATOR` | Interactive, sync | Yes | No | Live |
| `ChatService.buildMemorySelectionContext` | `respondForMemorySelection` | Responses, strict schema | `memorySelectionModel` (gpt-4o-mini) | `MEMORY_SELECTION` | Interactive, sync | Yes | No | Live |
| `NaturalLanguageComposer.compose` (TEXT) | `respondForComposer` | Responses, free text | `composerModel` (gpt-4.1) | `ANSWER_COMPOSER` | Interactive, sync | Yes | No | Live |
| `NaturalLanguageComposer.compose` (JSON) | `respondWithJson` | Responses, `json_object` | `chatModel` (intentionally not tiered) | `ANSWER_COMPOSER` | Interactive, sync | Yes | No | Live |
| `NaturalLanguageComposer.compose` (STRICT_JSON) | `respondWithStrictJsonForComposer` | Responses, strict schema | `composerModel` | `ANSWER_COMPOSER` | Interactive, sync | Yes | No | Live |
| `AgentRunner.run` (main loop) | `respondWithTools` | Responses, function tools | `chatModel` | `AGENT_RUNNER` | Interactive, sync | Yes | Yes | Live (Phase 9C) |
| `AgentRunner.salvageAnswer` | `respondWithTools` | Responses, function tools | `chatModel` | `AGENT_RUNNER_SALVAGE` | Interactive, sync | Yes | Yes | Live |
| `AgentToolRegistry.execAnalyzeImage` | `analyzeImage` | Chat Completions, vision | `chatModel` | `AGENT_TOOL_IMAGE_ANALYSIS` | Interactive, sync (nested tool call) | Yes | No | Live |
| `ChatService.resolveAgent` | `chatWithJsonFast` | Chat Completions, JSON | `routingModel` (gpt-4o-mini) | `AGENT_ROUTER` | Interactive, sync | Yes | No | Live |
| `ZevraAgentRouter.route` | `chatWithJsonFast` | Chat Completions, JSON | `routingModel` | `ZEVRA_AGENT_ROUTER` | Interactive, sync | Yes | No | Live — separate router implementation, different caller |
| `ChatService.getLlmDecision` (`@Deprecated`) | `chat` | Chat Completions, free text | `chatModel` | `DECISION_ROUTER` | Interactive, sync | Yes | No | Live, conditional (§5) |
| `LlmExecutionStrategySelector.analyze` | `chatWithJsonFast` | Chat Completions, JSON | `routingModel` | `EXECUTION_STRATEGY_SELECTOR` | Interactive, sync | Yes | No | Live, config-gated |
| `AttachmentProcessingService.extractFromImage` | `analyzeImage` | Chat Completions, vision | `chatModel` | `IMAGE_ATTACHMENT_EXTRACTION` | Interactive, sync | Yes (chat attachment) | No | Live |
| `TeachingService.buildProposal` | `respondWithStrictJson` (4-arg) | Responses, strict schema | `chatModel` | `TEACHING_PROPOSAL` | Interactive, sync | No (admin/teaching UI action) | No | Live |
| `OnboardingService.recommendTables` | `chatWithJsonForOnboarding` | Chat Completions, JSON | `onboardingModel` (gpt-4o-mini) | `ONBOARDING_RECOMMEND` | Background, sync (within async job) | No | No | Live |
| `BusinessObjectBatchAnalyzer.analyzeBatch` (shared) | `chatWithJsonForOnboarding` | Chat Completions, JSON | `onboardingModel` | caller-set: `ONBOARDING_BATCH_ANALYSIS` / `DISCOVER_BATCH_ANALYSIS` / `PACK_CONCEPT_CLASSIFICATION` | Background/sync depending on caller | No | No | Live, shared by 3 callers |
| `EnterpriseMapService.simulate` | `chatWithJsonForOnboarding` | Chat Completions, JSON | `onboardingModel` | `ENTERPRISE_MAP_SIMULATE` | Interactive, sync | No | No | Live |
| `PackEntityMapper.llmMatch` | `chat` | Chat Completions, free text | `chatModel` | `PACK_ENTITY_MAPPING` | Interactive, sync | No | No | Live |
| `DocumentMemoryService.indexDocument` | `embed` | Embeddings | `embeddingModel` | `MEMORY_EMBEDDING` | Background, `@Async` | No | No | Live |
| `DocumentMemoryService.retrieveContext` | `embed` | Embeddings | `embeddingModel` | `MEMORY_EMBEDDING` | Interactive, sync | Yes (RAG lookup) | No | Live |
| `TermExtractor.extract` | `chatForTermExtraction` | Chat Completions, free text | `termExtractorModel` (gpt-4o-mini) | `TERM_LEARNING_ASYNC` | Background, `@Async` | No | No | Live |
| `CorrectionDetector.detect` | `chatForCorrectionDetection` | Chat Completions, free text | `correctionDetectorModel` (gpt-4o) | `CORRECTION_DETECTION` | Background, `@Async` | No | No | Live |
| `AutomationGeneratorService` (analyze) | `chatWithJson` | Chat Completions, JSON | `chatModel` | `AUTOMATION_GENERATOR_ANALYZE` | Interactive, sync | No | No | Live |
| `AutomationGeneratorService` (generate) | `chatWithJson` | Chat Completions, JSON | `chatModel` | `AUTOMATION_GENERATOR_GENERATE` | Interactive, sync | No | No | Live |
| `ImageAnalyseExecutor.execute` | `analyzeImage` | Chat Completions, vision | `chatModel` | `AUTOMATION_IMAGE_ANALYSIS` | Background (workflow run) | No | No | Live |
| `AzureOpenAiClient.chatWithTools` | — | Chat Completions, function tools | `chatModel` | n/a | n/a | n/a | Yes | **Dead in production** (§4) |

## 3. Remaining Chat Completions

For every remaining production Chat Completions call, in the order requested:

- **`AgentToolRegistry.execAnalyzeImage` / `AttachmentProcessingService.extractFromImage` /
  `ImageAnalyseExecutor.execute`** (all `analyzeImage`, vision). Category: specialized one-shot
  vision task. Why still Chat Completions: vision analysis has no dependency on tool-calling,
  structured output, or conversation chaining — it's a single image-in/text-out call. Responses
  API offers no clear benefit here (no File Search, no tool loop, no multi-turn need). Migration
  not justified; recommend leaving alone.
- **`ChatService.resolveAgent` / `ZevraAgentRouter.route` / `LlmExecutionStrategySelector.analyze`**
  (all `chatWithJsonFast`, routing/classification). Category: lightweight, single-turn
  classification calls (which agent, which strategy). Why still Chat Completions: these predate
  the Responses migrations and were never in scope for Phases 1–4/9C (which targeted the
  conversational Agent Brain pipeline specifically, not every routing decision). Responses API's
  main advantages (File Search, `previous_response_id` chaining, function tools) are irrelevant to
  a single classification call. **INFERENCE:** migrating these would be a transport-only change
  with no functional benefit, similar in shape to Phase 9C but for calls with no tool-calling or
  chaining need — low value, non-zero risk (new payload shape to verify), not evidence-justified
  right now.
- **`ChatService.getLlmDecision`** — see §5, a distinct, conditionally-reachable case with its own
  deprecation note already in the code.
- **`TeachingService.buildProposal`** — already Responses (`respondWithStrictJson`), not Chat
  Completions; included in the inventory for completeness, not a remaining-Chat-Completions case.
- **`OnboardingService.recommendTables` / `BusinessObjectBatchAnalyzer.analyzeBatch` /
  `EnterpriseMapService.simulate`** (all `chatWithJsonForOnboarding`). Category: onboarding/discovery
  bulk analysis (Phase 5 territory — investigated and explicitly NOT migrated to Batch API; not
  part of any Responses migration scope either). Why still Chat Completions: loose JSON-object
  mode is sufficient for this contract; no File Search, no tool calling, no multi-turn chaining
  need. Responses API offers no demonstrated benefit. Better left alone.
- **`PackEntityMapper.llmMatch`** — free-text Chat Completions fallback for entity-name matching
  when deterministic matching fails. Single-shot, no conversational state. No migration benefit
  shown.
- **`DocumentMemoryService`'s `embed` calls** — Embeddings API, not Chat Completions at all;
  Responses API migration is not applicable to embeddings (different endpoint entirely).
- **`TermExtractor.extract` / `CorrectionDetector.detect`** — background, `@Async`, one-shot
  extraction/detection tasks feeding the semantic-learning pipeline. No conversational state, no
  tool calling. No migration benefit shown.
- **`AutomationGeneratorService`'s two calls** — one-shot workflow-generation tasks (analyze
  requirement, generate graph). JSON-object mode already matches the need; no File Search/tool
  calling/chaining requirement.

**RECOMMENDATION (evidence-based, not default-to-migrate):** none of the remaining Chat
Completions calls show a concrete, demonstrated need for a Responses API feature (File Search,
strict structured output beyond what `chatWithJson`/`response_format=json_object` already
provides, function-tool calling, or `previous_response_id` chaining). Migrating any of them would
be transport churn without a functional driver — consistent with the Phase 9A/9C precedent of only
migrating where a genuine architectural need (Stage 1's File Search, AgentRunner's tool loop)
existed.

## 4. AgentRunner Legacy Transport

**FACT — call graph for `AzureOpenAiClient.chatWithTools`:**
```
Production (src/main/java): NO CALLERS FOUND.
AgentRunner.java (both call sites) now call respondWithTools, not chatWithTools (Phase 9C).

Test callers (src/test/java):
  - ChatWithToolsMultipleToolCallsBaselineTest — directly exercises chatWithTools's HTTP-response
    parsing (Phase 9B baseline, documents the OLD Chat Completions behavior; still valid, still
    passes, since chatWithTools's own code is untouched).
  - PromptHardeningLiveProbe — UPDATED in Phase 9C to call respondWithTools instead; no longer
    calls chatWithTools.
  - AzurePayloadCaptureTest — UPDATED in Phase 9C to call respondWithTools instead.
  - AgentReadOnlyFlowIntegrationTest / AgentRunnerBehaviorBaselineTest — their scripted fakes now
    override respondWithTools, not chatWithTools (Phase 9C).
```
So after Phase 9C, the ONLY remaining reference to `chatWithTools` from test code is
`ChatWithToolsMultipleToolCallsBaselineTest`, which exists specifically to document the pre-migration
baseline (per its own javadoc) — not to exercise a still-active production path.

**FACT:** `chatWithTools`'s own supporting types (`AgentMessage`, `AgentToolResponse`) are still
used — but by `respondWithTools` and `AgentRunner`, not because of `chatWithTools`. They are not
orphaned; `chatWithTools` itself is.

**INFERENCE:** `chatWithTools` is dead production code, retained only as (a) a passive method on
the client with no callers, and (b) the subject of one still-passing historical baseline test.

**RECOMMENDATION:** `chatWithTools` is a candidate for removal in a future, narrowly-scoped
cleanup phase — but only together with either deleting or explicitly re-purposing
`ChatWithToolsMultipleToolCallsBaselineTest` (its only remaining caller), since removing the method
without addressing that test would break compilation. Not performed in this investigation-only
phase.

## 5. Decision Router

**FACT — exact reachability, `ChatService.java:537-565`:**
```java
if (businessModel.routingDecision().isPresent()) {
    // consume Stage 1's combined routing decision verbatim
    decisionRouterCalled = false;
} else {
    decision = getLlmDecision(...);   // the ONLY call site
    decisionRouterCalled = true;
}
```
`businessModel` always comes from the 6-argument `AgentBrain.resolve(...)` overload (the only one
`ChatService.ask()` calls), which always takes the `memoryAvailable`-non-null branch
(`AgentBrain.java:148-159`) and therefore always attempts the combined
`resolveObjectKeysWithRouting` path — never the non-routing path.

**FACT:** `routingDecision()` ends up `Optional.empty()` — and therefore `getLlmDecision()` fires
— under any of these proven conditions:
1. No `ConceptScopedMetadataResolver` wired, or no connection keys in scope
   (`AgentBrain.java:335-337`).
2. Stage 1 genuinely doesn't apply to the connection: no active Industry Pack, no pack record, or
   an empty tenant concept catalog for that connection (`ConceptScopedMetadataResolver.java:220-264`,
   returns `CombinedResolution.EMPTY`).
3. The model's own combined JSON response has a missing/invalid `routing.type` (`parseRouting`
   returns `null`, `ConceptScopedMetadataResolver.java:673-690`).
4. A caught (non-`NexusException`) failure inside Stage 1's resolution degrades to
   `CombinedResolution.EMPTY`.

**FACT:** This is proven, not inferred — `ChatServiceDecisionRouterAbsorptionCallCountTest` asserts
both `decisionRouterLlmCallIsNeverMadeWhenCombinedRoutingIsPresent()` (0 calls) and
`decisionRouterLlmCallStillFiresWhenRoutingIsAbsentLegacyFallback()` (exactly 1 call).

**FACT:** There is no config flag anywhere in `src/main/resources` gating this — the branch is
determined entirely by whether Stage 1's per-connection resolution actually produced a routing
decision, which in turn depends on tenant/connection onboarding state (has an Industry Pack with a
populated concept catalog been applied) and the model's own output quality, not on a toggle.

**RECOMMENDATION:** `getLlmDecision()` is **genuinely still required** for any tenant/connection
that has not completed Industry Pack + concept-catalog onboarding, and as a defensive fallback for
a malformed routing field. It must not be removed. The `@Deprecated` annotation should be
understood as "superseded as the primary path, but still load-bearing for a real class of
requests" — not "dead."

## 6. Stage 1 Current Architecture

```
[Chat / conversational path]                    [Autonomous Agent path]
ChatService.ask()                                AgentRunner.run()
  → AgentBrain.resolve(6-arg, memoryAvailable)     → AgentBrain.resolve(2-arg)
  → conceptScopedModelWithRouting                  → conceptScopedModel
  → resolveObjectKeysWithRouting                   → resolveObjectKeys / resolveConceptKeys
  → selectConceptsAndRoutingViaPersistentKnowledge  → selectConceptsViaPersistentKnowledge
  → chatWithFileSearchForConceptAndRouting          → chatWithFileSearch
    (combined concept + routing, strict schema)       (concept only, no routing)
  → ResolvedBusinessModel{conceptScoped,             → ResolvedBusinessModel{conceptScoped,
     routingDecision=Optional.of(...),                  routingDecision=Optional.empty(),
     resolvedConceptKeys, conceptAmbiguityClarification} resolvedConceptKeys}
```

**FACT:** These are **two distinct, both-live production call graphs**, not one canonical path
with a legacy remainder — cleanly split by caller (Chat vs. autonomous Agent), each calling a
different pair of `ConceptScopedMetadataResolver` public methods. Neither is dead or test-only.

**FACT:** No unused prompt constants, DTOs, or parsing methods were found in
`ConceptScopedMetadataResolver.java`/`AgentBrain.java` — every prompt constant, schema, and parser
is reachable from one of the two live call graphs above.

**FACT:** No feature flag or config key controls which Stage 1 path runs — the choice is purely
which `AgentBrain.resolve` overload the caller invokes (a compile-time/call-site decision, not a
runtime toggle).

**FACT:** The one genuinely legacy artifact from the pre-absorption architecture is entirely
inside `ChatService.java` — `DECISION_SYSTEM_PROMPT` (`@Deprecated`) and `getLlmDecision()`
(`@Deprecated`), covered in §5.

## 7. Responses API Infrastructure

**FACT:** The current Responses-API method family on `AzureOpenAiClient`:
`respond`, `respondWithJson`, `respondWithStrictJson` (4-arg), `respondForMemorySelection`,
`respondForPlanner`, `respondForEvaluator`, `respondForComposer`, `respondWithStrictJsonForComposer`,
`respondWithTools`, `chatWithFileSearch` (3 overloads), `chatWithFileSearchForConceptAndRouting`.

**FACT:** Every one of these funnels through the same two private builders
(`doRespond`/`respondWithStrictJson`-private, or their own inline request construction for
`chatWithFileSearch*`/`respondWithTools`) and the same shared transport (`executeWithRetry`) and
usage recording (`recordResponsesUsage`, with `recordFileSearchUsage` as a documented,
deliberate near-duplicate that adds one extra `file_search_call` scan). This is not proliferating
divergent implementations — it's one transport core with several thin, purpose-named wrappers,
each existing because it pins a different model/cache-key/schema combination (a pattern already
established and intentional since Phase 1's explicit prompt-caching work).

**FACT:** Each `respondFor*` wrapper exists specifically to hold one workload's dedicated model
field and cache key constant — removing them in favor of one generic parameterized method would
require every call site to pass model/cache-key/schema explicitly, which is exactly the kind of
"generic LLM gateway" the Phase 5/8/9 investigations were repeatedly instructed not to build.

**INFERENCE:** The method count (11 Responses-family methods) reflects one-per-workload naming for
clarity and call-site safety (a caller cannot accidentally use the wrong model/cache key), not
unnecessary duplication. Consolidating them would trade compile-time clarity for a runtime
parameter-passing burden.

**RECOMMENDATION:** Leave the Responses API infrastructure as-is. It is already "clean enough to
leave alone" (item 5 of the task's own classification) — there is no premature-abstraction risk
here because no abstraction has been introduced; each method is a plain, non-generic wrapper.

## 8. API Family Consistency

**FACT — single-investigation API-family map for the Chat conversational path:**
```
AGENT_ROUTER (chatWithJsonFast, Chat Completions)      ← only when >1 active agent
  ↓
STAGE1_FILE_SEARCH_CONCEPT_AND_ROUTING (Responses)
  ↓
[DECISION_ROUTER (chat, Chat Completions) — only if routing absent, §5]
  ↓
MEMORY_SELECTION (respondForMemorySelection, Responses) ← only if chatMemoryEnabled
  ↓
PLANNER / EVALUATOR loop (respondForPlanner/respondForEvaluator, Responses)
  ↓
ANSWER_COMPOSER (respondForComposer/respondWithJson/respondWithStrictJsonForComposer, Responses)
```
**FACT:** A single Chat request DOES routinely cross API families today — via `AGENT_ROUTER`
(Chat Completions, only fires when more than one agent is active) and conditionally via the
legacy Decision Router (Chat Completions, §5). This is not a migration gap in the core
Stage1→Planner→Evaluator→Composer loop (which is entirely Responses API) — it is these two
adjacent, narrower-purpose calls that were never in scope for the Responses migrations.

**FACT — AgentRunner path:** `AgentRunner`'s own loop is entirely Responses API
(`respondWithTools`), but its nested `analyze_image` tool call uses Chat Completions
(`analyzeImage`, vision) — a deliberate, unrelated capability (vision has no Responses-specific
need, §3).

**INFERENCE:** "Same API family everywhere" is not automatically better here — `AGENT_ROUTER` and
`getLlmDecision` are both low-complexity, low-volume classification calls where Responses API's
distinguishing features (File Search, tool calling, chaining) are irrelevant; forcing them onto
Responses API purely for consistency would be exactly the kind of unjustified transport churn
this investigation is meant to avoid recommending.

## 9. Conversation State Ownership

**Zevra-owned (FACT, confirmed unchanged post-9C):**
- `ConversationMemoryService` roster (entity discovery), keyed by `conversationId`.
- `ExecutionReference` (per-execution result/binding facts), the mechanism `ChatService` uses to
  build cross-turn `conversationContext` prose for both the Chat path and (via `AgentRunner`'s
  `conversationContext` parameter) the routed-agent path.
- `ZevraSession` (one row per completed `AgentRunner.run()` — write-once audit, never read back
  into a later run).
- `ResolvedBusinessModel`/`ExecutionContract` (per-request, not persisted across turns as an
  object — rebuilt every turn from Postgres-backed semantic/physical metadata).
- Tenant/connection identity (`TenantContext`, `TenantAwareDataSource`).
- Learned knowledge (`nexus_learned_mapping`), clarification state (in-request, not a stored
  session object).

**OpenAI-owned (FACT):**
- Stage 1's `previous_response_id` chain — the ONLY place this exists in the codebase
  (`ConceptScopedMetadataResolver`'s two combined/non-combined methods). Zevra stores the *string
  value* of the latest response id (via `tenantSettingsRepository`, keyed
  `RESPONSE_ID_SETTING_KEY_PREFIX + conversationId`), but the conversational context that id
  represents is reconstructed server-side by OpenAI, not by Zevra.
- No other Responses-family call (Planner/Evaluator/Composer/Memory Selection/AgentRunner) uses
  `previous_response_id` at all (confirmed by repo-wide grep, §10) — each of those calls resends
  its own full context every time, entirely Zevra-reconstructed.

**FACT:** Ownership is clear and non-overlapping: exactly one subsystem (Stage 1) delegates any
conversational continuity to OpenAI-side state; every other subsystem is fully Zevra-reconstructed
per call.

## 10. Context Duplication

**FACT — repo-wide grep confirms `previous_response_id`/`previousResponseId` appears ONLY in
`ConceptScopedMetadataResolver.java` and its supporting `AzureOpenAiClient.chatWithFileSearch*`
methods.** `respondWithTools`'s own javadoc explicitly documents its deliberate non-use
(`AzureOpenAiClient.java:1189`). No Agent Brain downstream call (Planner/Evaluator/Composer/Memory
Selection) or AgentRunner uses it.

Duplication classification, per the categories requested:

- **A. Intentional duplication:**
  - Learned business knowledge appears in both the Vector Store (Stage 1's File Search retrieval,
    server-side only, never forwarded to Java — confirmed in the Phase 8 investigation) and the
    Planner's own `=== LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT ===` block (Java's own Postgres
    query). These serve two different consumers for two different purposes (concept selection vs.
    SQL/literal grounding) and Stage 1 never forwards its copy — so this is not duplicate
    transmission within one request, it's the only channel for each consumer. Unchanged since
    Phase 8 (§11).
  - The user's raw question is naturally repeated across Stage 1 → Planner → Evaluator →
    Composer's own prompts — each stage needs the original question in its own context to reason
    about it; this is standard multi-stage-pipeline context, not redundant transmission.
  - Physical schema/legal values are rendered once, in the Planner's own `=== TABLE SCHEMA ===`
    block — not duplicated elsewhere in the pipeline (Stage 1 never receives physical schema at
    all, by design).

- **B. Potentially unnecessary duplication:**
  - The RESOLUTIONS-vs-LEARNED-BUSINESS-KNOWLEDGE within-Planner-prompt overlap identified in
    Phase 8 (§11) — unchanged, still present, still not safely fixable without a new join key.
  - Execution grounding (`conversationContext`, built from `ExecutionReference`) is passed into
    BOTH `ChatService`'s own `buildContextSummary` (feeding the Planner) AND, separately, into
    `AgentRunner.composeFirstMessage` for the routed-agent path — these are two different
    consumers (ReasoningEngine's Planner loop vs. AgentRunner's ReAct loop) of the same underlying
    fact, each needing its own copy since they are different call trees. **This is category B
    only in the narrow sense that the same fact is computed once (`buildExecutionGrounding`) and
    then handed to two different consumers — not literally duplicated computation, and not proven
    safe or unsafe to reduce without deeper tracing than this phase performed.**

- **C. Cannot determine safely without runtime capture:** whether the Composer's own prompt
  re-includes any of Planner/Evaluator's evidence in a way that duplicates what the model already
  saw in an earlier turn of the SAME investigation (via `EvidenceStore`) would require comparing
  actual rendered prompts across a real multi-step investigation — not performed in this
  investigation-only pass (consistent with Phase 8's own conclusion not to fabricate such an
  analysis without real captures).

**RECOMMENDATION:** No removal proposed. Category A duplication is correctly intentional. Category
B items require the Phase 8-identified schema change (out of scope) or further tracing before any
action.

## 11. Learned Knowledge

**FACT:** Re-inspected post-9C. Neither `ConceptKnowledgeMaterializationService` (Vector Store
projection) nor `ChatService.buildContextSummary`'s `=== LEARNED BUSINESS KNOWLEDGE FOR THIS
CONCEPT ===` block, nor `AzureOpenAiClient.extractResponseText`'s restriction to `message`-type
output items, were touched by Phase 9C (which only modified `AgentRunner`/`respondWithTools`'s
tool-calling path). Phase 8's central finding — Stage 1 never forwards File Search retrieval
content to any downstream stage, so Planner's own Postgres-backed learned-knowledge block is the
only channel that reaches Agent Brain's reasoning — remains structurally unchanged.

**CONCLUSION:** Phase 8's conclusion ("do not change anything; the two paths serve different
responsibilities") is still valid. Nothing in Phases 9A–9D invalidates it.

## 12. Call Elimination Candidates

| Candidate | Current call | Evidence | Why possibly redundant | What must be proven first | Risk |
|---|---|---|---|---|---|
| `getLlmDecision()` (Chat Completions) | `ChatService.getLlmDecision` | §5: fires for non-onboarded tenant/connections or malformed routing | Stage 1 "should" always own routing once every tenant is onboarded | That every production tenant/connection has an active Pack + populated concept catalog, AND that Stage 1's routing field never comes back malformed — neither is proven; onboarding state varies per tenant | High — removing would break routing for any not-yet-onboarded connection, with no fallback |
| `ZevraAgentRouter.route` vs. `ChatService.resolveAgent` | Two separate `chatWithJsonFast` implementations for agent selection | Both exist, both tagged differently, both call `chatWithJsonFast` | Same underlying task (pick the right agent) implemented twice | Confirm whether both are reachable from the SAME request path or genuinely different entry points (e.g. `ZevraAgentController` vs. `ChatService`'s multi-agent chat) — not fully traced in this pass | Medium — consolidating without confirming both are truly equivalent could change agent-selection behavior for one of the two entry points |
| `chatWithTools` (dead) | none (production) | §4 | Superseded by `respondWithTools` | Confirm `ChatWithToolsMultipleToolCallsBaselineTest` is deliberately retired/deleted alongside it | Low — no production reachability, only a compile dependency from one test |

**FACT, not inference:** none of these three candidates is safe to remove today without the
listed proof step. No other elimination candidates were found — every other call site inventoried
in §2 has a distinct, still-needed purpose (a different workload, model, or capability).

## 13. Dead Code / Configuration

| Symbol | Location | References | Production reachable? | Test-only? | Recommendation |
|---|---|---|---|---|---|
| `AzureOpenAiClient.chatWithTools` | `AzureOpenAiClient.java:1112` | 0 production, 1 test (`ChatWithToolsMultipleToolCallsBaselineTest`) | No | Yes | INVESTIGATE (removal candidate, paired with its one test, §4) |
| `ChatService.DECISION_SYSTEM_PROMPT` (`@Deprecated`) | `ChatService.java:1041` | Used only by `getLlmDecision` | Yes (indirectly, via §5's live conditions) | No | KEEP |
| `ChatService.getLlmDecision` (`@Deprecated`) | `ChatService.java:1247` | Called conditionally, proven live by tests | Yes | No | KEEP |
| `ReasoningEngine`'s legacy `queryData` compatibility path | `ReasoningEngine.java:454` (comment), `ChatResponse.queryData` | Still consumed by frontend DataTable/DataViz/SuggestedQuestions and `ScheduledReportService`/`ReportHtmlComposer` per the code's own comment | Yes | No | LEAVE ALONE — removal is a frontend-coordinated change, out of this investigation's LLM-architecture scope |
| `ResponseArtifactsBuilder`'s "legacy fallback" chart/shape-inference heuristic | `ResponseArtifactsBuilder.java` (multiple) | Runs only when the model didn't produce structured per-step artifacts | Yes (defensive path) | No | LEAVE ALONE |
| `zevra.strategy.selector.enabled` | `LlmExecutionStrategySelector.java:47` | Referenced in production dispatch logic (`:63-71`); not present in `application.yml` (code default `true`) | Yes | No | KEEP — explicitly documented as an "instant, config-only rollback," i.e. deliberately retained safety valve, not dead |
| `zevra.agent.memory-tools.enabled` | `AgentToolRegistry.java:81` | Gates 6 tool definitions; not present in `application.yml` (code default `false`) | Yes | No | KEEP — same rollback-flag pattern |
| `nexus.openai.routing-model` | `AzureOpenAiClient.java:50` | Used by `chatWithJsonFast`, absent from `application.yml` | Yes (via code default) | No | INVESTIGATE — not a dead flag, but its absence from `application.yml` alongside 8 sibling model-tiering keys that ARE present looks like an oversight, not a deliberate omission; worth confirming with whoever maintains deployment config, not something to change here |
| `nexus.openai.max-concurrent-calls` | `AzureOpenAiClient.java:113` | Global throttle semaphore, absent from `application.yml` | Yes (via code default 6) | No | INVESTIGATE — same oversight-vs-deliberate question as above |

**FACT:** No `@Deprecated` symbol was found anywhere in the codebase without at least one live
production reference — the two `@Deprecated` items in `ChatService.java` are both proven reachable
(§5), not vestigial.

## 14. Execution Policy Layer

**FACT:** Repeated *decisions* about model/API-family/structured-output/prompt-caching/retry/call-type
do exist, but they are not duplicated logic — they are one decision per workload, expressed as one
dedicated `@Value`-backed field (model) and one dedicated wrapper method (API shape/schema/cache
key), all funneling through the same two or three shared transport primitives
(`executeWithRetry`, `recordUsage`/`recordResponsesUsage`, `LlmCallTag`). There is no evidence of:
- the same model-selection logic being reimplemented in more than one place,
- retry/backoff being reimplemented anywhere outside `AzureOpenAiClient`'s single shared
  `executeWithRetry`/`throttledSendWithRetry`/`sendWithRetryLocked` chain,
- structured-output wiring being duplicated rather than reused (`respondWithStrictJson`'s
  private core is reused by both `respondForPlanner`/`respondForEvaluator`/`respondForMemorySelection`
  and `TeachingService`),
- telemetry being recorded through more than the two documented shapes
  (`recordUsage` for Chat Completions, `recordResponsesUsage` for Responses API).

**CONCLUSION: a centralized execution policy layer is NOT justified now.** The complexity a policy
layer would exist to tame — many divergent, ad-hoc call-construction implementations — is not
present. What exists instead is intentional, low-risk, per-workload specialization over a shared
core, which is exactly the pattern Phases 1–4 deliberately built and every subsequent phase (5, 8,
9A–9C) has been explicitly instructed not to collapse into a generic gateway. Building one now
would be premature abstraction against a problem the evidence does not show.

## 15. Recommended Next Actions

At most three, ordered by architectural value. **None are being implemented in this phase.**

1. **Add regression tests pinning `getLlmDecision()`'s exact reachability conditions as a
   dependency-tracked contract**, beyond the existing call-count test.
   - *Why:* §5 proves this method is genuinely load-bearing for non-onboarded tenants, but its
     reachability depends on the interaction of several independent conditions (pack applied,
     concept catalog populated, model's routing field well-formed). A future change to any of
     those systems (Pack apply flow, concept sync, Stage 1 prompt) could silently shift how often
     this fires without any test noticing, since the only current test checks the two boundary
     cases, not the underlying conditions.
   - *Evidence:* §5, `ChatServiceDecisionRouterAbsorptionCallCountTest`'s existing two tests.
   - *Scope:* test-only; no production change.
   - *Risk:* very low — additive tests only.
   - *Justified:* yes, low-cost and directly protects a proven-live legacy path from silent
     regression.

2. **Retire `chatWithTools` together with `ChatWithToolsMultipleToolCallsBaselineTest`.**
   - *Why:* §4 proves zero production reachability; the method exists solely to keep one
     historical baseline test compiling.
   - *Evidence:* full call-graph trace in §4.
   - *Scope:* delete one method + one test file (or convert the test into a comment/no-op
     historical note if the team wants to keep the documentation value without the dead code).
   - *Risk:* low — no production caller to break; the only consumer is the one test being
     retired alongside it.
   - *Justified:* yes, but this is code deletion, not a design change — smallest possible
     follow-up, not a "phase."

3. **Investigate the `application.yml` gap for `nexus.openai.routing-model` and
   `nexus.openai.max-concurrent-calls`** (confirm with whoever owns deployment config whether
   their absence, relying on code defaults, is deliberate).
   - *Why:* every sibling model-tiering key is present in `application.yml`; these two are not,
     which is either an intentional "no override needed" choice or an oversight.
   - *Evidence:* §13.
   - *Scope:* a config/documentation question, not a code change.
   - *Risk:* none from investigating; leaving it unresolved risks a future deploy assuming a
     value is configurable when it isn't (or vice versa).
   - *Justified:* yes, but this is a five-minute conversation, not an implementation phase.

**No larger architectural action (execution policy layer, further Responses migration, Stage 1
consolidation, learned-knowledge deduplication) is justified by current evidence.** The system is
in a coherent state: Responses API owns every conversational Agent Brain and AgentRunner call;
Chat Completions is confined to non-conversational, single-shot, or intentionally-out-of-scope
work; the two `@Deprecated` legacy items are both proven still necessary, not vestigial.
