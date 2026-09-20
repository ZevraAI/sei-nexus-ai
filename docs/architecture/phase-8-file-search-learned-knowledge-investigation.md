# Phase 8 — File Search / Learned Knowledge Deduplication

Investigation only. No production code was modified to produce this report.

## 1. Executive Summary

Two distinct, independently-owned mechanisms carry "learned business knowledge"
(`nexus_learned_mapping`) toward an LLM in this codebase:

1. **`ConceptKnowledgeMaterializationService`** projects promoted learned mappings
   (`business_term`/`sql_pattern`/`confidence`) as a `learned_knowledge` array *inside* each
   concept's own Vector Store JSON document. Stage 1 (`ConceptScopedMetadataResolver`) uses
   OpenAI's native `file_search` tool against that same Vector Store to select which
   `concept_key`(s) apply to the question.
2. **`ChatService.buildContextSummary`** independently queries
   `nexus_learned_mapping` by exact `concept_key` match
   (`SemanticLearningService.findPromotedByConceptKeys`) and renders every match verbatim into
   the `=== LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT ===` block of the Planner's prompt.

**These two paths do overlap in underlying data, but they are not simple redundancy that can
be safely removed.** The reason is structural, not incidental: Stage 1 **never forwards any
retrieved File Search content downstream**. `AzureOpenAiClient.extractResponseText` only reads
`output[].type=="message"` items; it never inspects the `file_search_call` item's `results`
(the only other reader, `recordFileSearchUsage`, logs a bare result *count*, never content).
`ResolvedBusinessModel` carries only concept identity (`resolvedConceptKeys`), a routing enum,
and short LLM-authored clarification strings — never retrieved document prose. Consequently,
even though Stage 1's model *may* incidentally see a concept's `learned_knowledge` array while
selecting that concept, **that fact never reaches the Planner through Stage 1's own return
path.** The `ChatService`/`LEARNED BUSINESS KNOWLEDGE` block is the *only* channel by which
learned business-term↔SQL-pattern evidence reaches Agent Brain's SQL-writing/literal-authority
reasoning. Removing it would not deduplicate anything — it would silently delete the only copy
Planner ever receives.

A second, narrower and more clearly duplicative overlap does exist **within the same Planner
prompt**: a promoted learning that was also formalized into `nexus_operational_vocabulary` (the
nightly-promotion side effect) can appear once in the `=== RESOLUTIONS ===` block (sourced from
`BusinessLanguageResolver` / vocabulary) and again in `=== LEARNED BUSINESS KNOWLEDGE FOR THIS
CONCEPT ===` (sourced from a direct `nexus_learned_mapping` query) — same underlying fact,
different tables, no deterministic join key between them today. This is real, but no safe
dedup is currently possible without adding a new column (see §10/§12) — attempting it any other
way would require Java to string-match `business_term` against `term`, which is exactly the
kind of semantic/fuzzy matching this architecture forbids Java from doing.

**Recommendation: do not change anything.** See §10.

## 2. Current Data Flow

```
Learning creation (4 write paths)
  ├─ SemanticLearningService.dispatch()            → CLARIFICATION_RESOLUTION → TermExtractor → upsert()
  ├─ SemanticLearningService.captureLiteralBinding()→ validated literal binding → upsert() (never promotes)
  ├─ TeachingService                                → explicit teaching → insertExplicitTeaching()
  └─ SemanticLearningService.reinforceFromFeedback() → positive feedback → reinforce() (existing rows only)
        ↓
nexus_learned_mapping  (promoted = FALSE, concept_key = NULL by default)
        ↓
Promotion (two paths, same effect):
  ├─ Nightly: SemanticLearningService.runMaintenanceAcrossTenants() (02:45 cron)
  │     use_count ≥ 10 AND confidence ≥ 0.80 → semanticService.createTerm(...) [nexus_operational_vocabulary row]
  │                                          → mappingRepository.markPromoted(mappingKey)
  └─ Manual: SemanticController.promoteLearning (admin endpoint) — same createTerm()+markPromoted(), immediate
        ↓
concept_key assignment — NEVER inferred. Admin-only, exact string, via:
  SemanticController.promoteLearning (optional, at promotion time)
  SemanticController.assignConcept   (backfill, any time after promotion)
        ↓  (only if concept_key is set AND promoted = TRUE)
        │
        ├──────────────────────────────┬───────────────────────────────────┐
        ↓                                                                  ↓
ConceptKnowledgeSynchronizationService.triggerAsync()          ChatService.buildContextSummary()
  (fired after promote/assignConcept/demote, pack apply/remove,   (fired on every live chat turn,
   manual "Sync Now", + nightly 03:15 reconciliation backstop)     independent of sync state)
        ↓                                                                  ↓
ConceptKnowledgeMaterializationService.collectConceptUnits()      SemanticLearningService
  → learnedMappingRepository.findPromotedByConceptKey(key)          .findPromotedByConceptKeys(resolvedConceptKeys)
  → builds JSON: {concept_key, name, aliases, description,          → LearnedMappingRepository
                  operational_meaning,                                 .findPromotedByConceptKeys(...)
                  learned_knowledge: [{surface, binding, confidence}]}  (exact concept_key IN (...) match)
        ↓                                                                  ↓
Uploaded as ONE file per concept to tenant's OpenAI Vector Store   Rendered verbatim into
        ↓                                                          "=== LEARNED BUSINESS KNOWLEDGE
Stage 1 (ConceptScopedMetadataResolver) file_search retrieves          FOR THIS CONCEPT ===" block
  this document server-side while selecting concept_key(s)                ↓
        ↓                                                          ReasoningPlanner.buildPrompt()
Stage 1 returns ONLY: resolvedConceptKeys, routing decision,               ↓
  optional short clarification string                              aiClient.respondForPlanner(...)
        ↓                                       (raw retrieved text discarded here — never forwarded)
ResolvedBusinessModel.resolvedConceptKeys ─────────────────────────→ feeds the RIGHT-HAND branch above
```

Both branches originate from the same `resolvedConceptKeys`, but Stage 1's branch (left) is
consumed entirely inside OpenAI's hosted model during the Stage 1 call and never leaves that
call; the Planner's branch (right) is Java's own independent, deterministic re-query of
Postgres.

## 3. Stage 1 Knowledge

**What is projected into the Vector Store** (`ConceptKnowledgeMaterializationService.
buildConceptKnowledgeJson`, one JSON file per concept):

```json
{
  "concept_key": "...",
  "name": "...",
  "aliases": ["...", "..."],
  "description": "...",
  "operational_meaning": "...",
  "learned_knowledge": [
    { "surface": "...", "binding": "...", "confidence": 0.85 }
  ],
  "pack": "...",
  "connection": "...",
  "generated_at": "..."
}
```
Sourced from: the static Industry Pack catalogue (`name`/`aliases`/`description`/
`operational_meaning`) plus `LearnedMappingRepository.findPromotedByConceptKey(conceptKey)`
(singular method — a separate query from the Planner's `findPromotedByConceptKeys`, plural).
**Not included**: `nexus_correction` (never queried by this class), `nexus_operational_vocabulary`
(never queried by this class — the vocabulary catalogue and the concept catalogue are unrelated
sources here).

**What Stage 1 actually returns to Java** (`ConceptScopedMetadataResolver` /
`AzureOpenAiClient.extractResponseText`): only the model's own final JSON message —
`{"metadataRequest": {"conceptKeys": [...], "resolutionType": ..., "conceptClarificationQuestion": ""}, "routing": {...}}`.
Confirmed by code: `extractResponseText` walks only `output[].type=="message" →
content[].type=="output_text" → text`; it never visits `output[].type=="file_search_call"` or
its `results` array. The only other reader of that item type,
`recordFileSearchUsage`, extracts solely `status` and `results.size()` (a count) for a metrics
log line — never content. A repo-wide grep for `annotations`/`citations`/`file_search_call`
confirms no code anywhere parses retrieved snippet text out of a File Search response.

**Conclusion:** Stage 1's model may see a concept's `learned_knowledge` while selecting that
concept (it is textually present in the retrieved document), but that content is consumed
entirely server-side by OpenAI's hosted model and is architecturally incapable of reaching
Java or any later stage — Java only ever reads the small structured JSON answer.

## 4. Planner Knowledge

`ChatService.buildContextSummary` (lines ~2246–2277), triggered on every live chat turn where
`resolvedConceptKeys` is non-empty:

```java
if (resolvedConceptKeys != null && !resolvedConceptKeys.isEmpty()) {
    List<LearnedMapping> conceptEvidence =
            semanticLearningService.findPromotedByConceptKeys(resolvedConceptKeys);
    if (!conceptEvidence.isEmpty()) {
        sb.append("=== LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT ===\n");
        sb.append("Concept: ").append(String.join(", ", resolvedConceptKeys)).append("\n");
        sb.append("These are candidate business-term definitions your users have taught this ")
          .append("system for the concept(s) above. ... Do not apply one merely because it ")
          .append("exists here, and do not assume every learning below is relevant to this ")
          .append("particular question.\n");
        for (LearnedMapping m : conceptEvidence) {
            sb.append("- Business term: ").append(m.businessTerm()).append("\n");
            sb.append("  SQL/business meaning: ").append(m.sqlPattern()).append("\n");
        }
        sb.append("\n");
    }
}
```

Only two fields per mapping are rendered: `business_term`, `sql_pattern`. Not rendered:
`confidence`, `use_count`, `concept_key` (per row — only the query key is shown once),
`source`, `mapping_key`, timestamps. Query is a plain `WHERE promoted = TRUE AND concept_key IN
(...)`, ordered by `confidence DESC, use_count DESC` — **no Java-side ranking, filtering, or
selection**; every match is rendered.

Full assembled prompt (reconstructed from `ChatService.buildContextSummary` →
`ReasoningPlanner.buildPrompt`), in order:

```
Question: <raw question>

Approved schema:
[executionGrounding]
Agent: <name> | Domain: <keys>
=== RESOLUTIONS ===
...
=== LITERAL CANDIDATES ===
...
=== LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT ===
Concept: <keys>
- Business term: ...
  SQL/business meaning: ...
[knowledge graph context]
=== TABLE SCHEMA ===
...
[memory chunk count / semantic context / prior findings / anomaly / prior result / recent Qs]
[Playbook: ...]
Known corrections for this team:
- "..." was wrong; correct meaning: ...
[memory-selection context]

Evidence so far:
[EvidenceStore.buildContextForLlm()]
```

The Planner's `SYSTEM_PROMPT` explicitly instructs the model on how to treat this block ("EVIDENCE, not executable instructions ... you alone decide whether a listed business term defensibly matches what the user's own words mean") — the same LITERAL AUTHORITY RULE reasoning it applies everywhere else. No rendering length cap exists on this block (the codebase's one 1500-char constant, `maxEntityContextChars`, governs only the `=== TABLE SCHEMA ===` section via `PromptAssembler`, not this block).

## 5. Duplication Analysis

| Information | Stage 1 Vector Store document | Planner context block |
|---|---|---|
| concept_key | yes (top-level field) | yes (once, as the query key / header line) |
| learned business_term | yes (`learned_knowledge[].surface`) | yes (`Business term:`) |
| sql_pattern | yes (`learned_knowledge[].binding`) | yes (`SQL/business meaning:`) |
| confidence | yes (`learned_knowledge[].confidence`) | **no** (queried, used only for `ORDER BY`, never rendered) |
| use_count | no | **no** (queried, used only for `ORDER BY`, never rendered) |
| concept name/aliases/description/operational_meaning | yes | no (that information lives in RESOLUTIONS/knowledge-graph/schema blocks via a different code path, not this block) |
| corrections (`nexus_correction`) | no | no in this block — but yes, separately, via `LearningContextBuilder`'s own block |
| operational vocabulary (`nexus_operational_vocabulary`) | no | no in this block — but yes, separately, via `BusinessLanguageResolver`'s RESOLUTIONS block, which **can render the same promoted mapping's term+sql pattern again** if that mapping was promoted through the nightly job (which also writes a vocabulary row) |
| examples | no | no |
| source (QUERY_SUCCESS / USER_CORRECTION / etc.) | no | no |
| Reaches which LLM call | Stage 1's concept-selection request only, server-side, discarded after | Planner's SQL-reasoning request — the only channel that reaches this call |

**Cross-block overlap inside the SAME Planner prompt** (not Stage-1-vs-Planner, but
Planner-vs-Planner): `=== RESOLUTIONS ===` (fed by `BusinessLanguageResolver` reading
`nexus_operational_vocabulary`) and `=== LEARNED BUSINESS KNOWLEDGE FOR THIS CONCEPT ===` (fed
by `nexus_learned_mapping` directly) can both show the same underlying learning's
term/SQL-pattern text, because nightly promotion writes both a vocabulary row (`term`,
`sql_equivalent`) and sets `promoted=TRUE` on the same source `nexus_learned_mapping` row — with
no foreign key or shared identifier linking the two rows back together today.

## 6. Actual Request Evidence

No live-tenant capture was performed in this investigation pass. Setting one up (a tenant with
a promoted learning already classified to a `concept_key`, a live OpenAI key, and a controlled
question) was judged out of proportion to what static code evidence already answers
definitively: Stage 1's *inability* to forward retrieved content is a structural fact provable
from `AzureOpenAiClient.extractResponseText`'s parsing logic alone (§3), not something that
needs a live run to falsify. What a live capture *would* additionally show — the actual token
cost of Stage 1 seeing `learned_knowledge` in a retrieved document — is not claimed here as a
quantified number; see §12 for why no cost/savings figure is asserted.

If this investigation's conclusion is later challenged, the concrete way to capture real
evidence is: pick a tenant with a promoted, concept-classified learning; ask a question that
triggers file_search retrieval of that concept; enable `-Dnexus.capture.payload.dir`/
`abortBeforeSend` (the existing capture-and-abort diagnostic mechanism, already used in Phases
1–4) on both the Stage 1 and Planner calls; compare the two captured request bodies directly.

## 7. Multi-Turn Analysis

Traced against the three-turn example ("show me open purchase orders" → "only for buyer
Marcus" → "what about submitted last month?"):

- `resolvedConceptKeys` is recomputed by Stage 1 **on every turn** (Stage 1 is not skipped on
  follow-ups) — so the Planner's LEARNED BUSINESS KNOWLEDGE block is rebuilt fresh each turn
  from whatever `resolvedConceptKeys` Stage 1 resolves for that turn's question, not cached from
  turn 1.
- `EvidenceStore` (prior-step results) and `previous_response_id` (OpenAI-side conversation
  chaining) are separate mechanisms from learned knowledge — removing the Planner's learned
  block would not affect result-set reuse or conversation continuity, since neither depends on
  it.
- Because Stage 1 never forwards retrieved knowledge and re-runs concept resolution every turn,
  a hypothetical "Stage 1 already showed this once, turn 1" argument for dropping Planner's copy
  on later turns does not hold — Stage 1 never surfaces it on ANY turn, first or later.
- Clarification turns: when Stage 1 returns `conceptAmbiguityClarification` or a routing
  `ASK_CLARIFICATION`, the request short-circuits before Planner runs at all in the ambiguity
  case (`ChatService` lines ~475-483) — no learned-knowledge block is built or needed for that
  turn regardless of this investigation's outcome.

## 8. Tenant Isolation

- Vector Store: `vectorStoreId` is resolved strictly from `TenantContext`'s schema inside
  `ConceptScopedMetadataResolver` (never client-supplied), and `ConceptKnowledgeMaterializationService`
  scopes every uploaded document by `(connectionKey, packKey, conceptKey)` with tenant-specific
  file attributes (`connection_key`, `pack_key`) — one tenant's Vector Store cannot contain
  another tenant's concept documents.
- Planner block: `LearnedMappingRepository.findPromotedByConceptKeys` runs against
  `nexus_learned_mapping`, a tenant-schema-resident table reached exclusively through
  `TenantAwareDataSource`'s `SET search_path` — a tenant's connection can only ever see its own
  schema's rows. `explicit teaching` additionally scopes by `connection_key` within a tenant.
- No code path in either mechanism accepts a caller-supplied tenant identifier that could cross
  tenants; both rely on the same `TenantContext`-driven connection routing already relied upon
  throughout the codebase.

## 9. Architecture Options

Presented without ranking, per instructions.

**Option A — Keep learned knowledge in both Stage 1 (Vector Store) and Planner (Postgres direct
query).** *(current state)*
- Semantic correctness: sound — each consumer gets its own copy for its own purpose.
- Grounding quality: full — Planner always has explicit, deterministic evidence regardless of
  what Stage 1's model happened to retrieve or use internally.
- Token impact: two separate calls each may include the same fact once; no single request is
  inflated by double-inclusion.
- Latency impact: none beyond what already exists.
- Implementation complexity: none (no change).
- Failure modes: unchanged — Vector Store staleness affects only Stage 1's concept selection
  (see §11), never Planner's block (which reads Postgres directly, always fresh).
- Tenant isolation: unaffected.
- Conversation continuation/clarification: unaffected (§7).
- Learned corrections: unaffected — corrections live in a separate table/block entirely.
- Java semantic responsibility: unchanged — zero.

**Option B — Stage 1 retrieves learned knowledge and passes the relevant retrieved evidence
directly to Planner.**
- Semantic correctness: would require Java (or a schema change) to extract `file_search_call`
  results text out of Stage 1's response and forward it — currently zero code does this.
- Grounding quality: potentially richer (raw retrieved snippets rather than a fixed two-field
  render) but also riskier — retrieved snippets are the FULL concept document (name, aliases,
  description, etc.), not just the learned-knowledge slice; forwarding it wholesale duplicates
  MORE than today, not less.
- Token impact: could increase (whole retrieved document forwarded) or be neutral, depending on
  extraction scope.
- Latency impact: negligible (no additional API round trip, just additional parsing).
- Implementation complexity: significant — new response-parsing code in `AzureOpenAiClient`
  (currently deliberately absent), a new field on `ResolvedBusinessModel`/`FileSearchResult`,
  and a decision about how to bound its size (the 1500-char rendering constraint mentioned in
  the task does not currently apply to any learned-knowledge path, per §4/§6 investigation — it
  governs `TABLE SCHEMA` only).
- Failure modes: new failure surface (malformed/absent file_search results) that doesn't exist
  today.
- Tenant isolation: unaffected in principle (data source unchanged).
- Conversation continuation/clarification: no interaction change expected.
- Learned corrections: unaffected.
- Java semantic responsibility: **risk** — deciding how much of a heterogeneous retrieved
  document to keep vs. discard could edge toward Java making a relevance judgment, unless the
  extraction is a mechanical, fully-inclusive passthrough (never a filtered/ranked subset).

**Option C — Planner retrieves learned knowledge independently.** *(this is what already
happens today, exact-`concept_key` match, no ranking)* — see Option A; this is not a distinct
state, it's a restatement of the Planner side of the current architecture.

**Option D — Planner receives only concept identity and deterministic runtime/schema context;
learned knowledge is no longer sent separately.**
- Semantic correctness: **broken** — per §3, Stage 1 never forwards retrieved knowledge to any
  downstream stage. Removing Planner's own query would leave Agent Brain with zero
  business-term↔SQL-pattern evidence for any concept, for every question, on every turn.
- Grounding quality: regressed — the LITERAL AUTHORITY RULE's "authoritative mapping" case
  (business phrase → legal value, e.g. "open" → a status IN-list) depends on this exact
  evidence being present in the Planner's own prompt; Stage 1's internal, discarded use of the
  same fact does not substitute for it.
  the same fact does not substitute for it.
- Token impact: would reduce Planner prompt size, but at the cost of the reasoning it currently
  enables — not a fair trade without an alternative evidence channel.
- Implementation complexity: trivial (deletion), but for a wrong reason.
- Failure modes: silent — nothing would error; the model would simply have no learned business
  term mapping evidence and would have to guess or ask for clarification more often, changing
  behavior without any code path noticing.
- Tenant isolation: unaffected.
- Conversation continuation/clarification: would likely increase clarification frequency for
  previously-learned business terms, since the model would lose its only evidence for them.
- Learned corrections: unaffected (separate mechanism).
- Java semantic responsibility: unaffected (no new logic added — this option is a pure removal).

**Option E — Deduplicate only the RESOLUTIONS-vs-LEARNED-BUSINESS-KNOWLEDGE within-Planner-prompt
overlap (§5), leaving Stage-1-vs-Planner untouched.**
- Semantic correctness: sound in principle — same underlying learning, two tables.
- Grounding quality: unaffected if done correctly (removes exact duplicate text, not the
  evidence itself).
- Token impact: a genuine, if likely small, per-request reduction — bounded by how often a
  promoted+concept-classified mapping's vocabulary counterpart also appears in RESOLUTIONS for
  the SAME turn's resolved entities/columns.
- Implementation complexity: requires a new deterministic link between
  `nexus_operational_vocabulary` and `nexus_learned_mapping` (e.g. a `source_mapping_key`
  column set at promotion time) — without it, any attempt to detect "is this vocabulary term
  the same as that learned mapping" would require comparing `term` against `business_term`
  strings, which is exactly the fuzzy/keyword matching this architecture prohibits Java from
  doing.
- Failure modes: a missing/stale link would just mean occasional residual duplication, not
  incorrect answers — safe failure mode, but only once the schema exists.
- Tenant isolation: unaffected.
- Conversation continuation/clarification: unaffected.
- Learned corrections: unaffected.
- Java semantic responsibility: **safe only if implemented via an exact-key join** (mapping_key
  ↔ a new vocabulary column) — never via string comparison of `term`/`business_term`.

## 10. Recommendation

**Do not change anything now.** The two paths (Stage 1's Vector Store projection vs. Planner's
direct Postgres query) serve different responsibilities and different consumers: Stage 1's copy
is consumed entirely inside a separate, earlier OpenAI call for concept *selection* and never
leaves that call; Planner's copy is the *only* channel through which learned business-term↔SQL
evidence reaches Agent Brain's SQL-writing and literal-authority reasoning. There is no proven
redundant transmission to eliminate between these two — eliminating Planner's copy would not
deduplicate anything, it would delete Agent Brain's only source of this evidence.

The one concretely-identified, narrower overlap (§5/§9 Option E — RESOLUTIONS vocabulary text
vs. LEARNED BUSINESS KNOWLEDGE text, within the same Planner prompt, for a promoted mapping that
was also formalized into `nexus_operational_vocabulary`) is real but is **not safe to fix today**
without first adding a deterministic join key (a new nullable column, e.g.
`nexus_operational_vocabulary.source_mapping_key`, set once at nightly-promotion time
alongside the existing `createTerm`/`markPromoted` calls). That is a schema change with its own
regression surface and is explicitly out of this investigation's scope ("no speculative
cleanup," "no unrelated refactoring"). It is recorded here as a candidate for a future,
narrowly-scoped phase — not implemented in this one.

No implementation is proposed. Per the phase's own default action, this investigation stops
here.

## 11. Required Regression Tests

Listed for reference should any future phase act on §9/§10's findings — not implemented in this
phase:

1. "show me all open purchase orders" (promoted learning present, concept resolved)
2. "show me all open orders" (paraphrase, same concept)
3. "show purchase orders for Marcus" (no relevant learned mapping)
4. A question with a promoted learned mapping under the resolved concept
5. A question with no learned mapping for the resolved concept
6. A clarification scenario (Stage 1 `ASK_CLARIFICATION` / ambiguity short-circuit)
7. A follow-up turn reusing/narrowing a prior result set
8. Multiple resolved concepts in one turn
9. An unsupported literal requiring clarification (LITERAL AUTHORITY RULE path)
10. Tenant A's learned mapping must never appear in Tenant B's Planner context or Vector Store
11. Stale/failed Vector Store synchronization (Postgres promoted+classified, sync FAILED/PARTIAL)
12. Vector Store unavailable/File Search error during Stage 1
13. No promoted learning exists anywhere for the tenant
14. Multiple promoted learnings under one concept_key (ordering by confidence/use_count, all
    rendered, none dropped)

## 12. Risks

- **No live-tenant/live-OpenAI capture was performed** in this pass (§6) — the conclusions rest
  on exhaustive static code tracing (three independent deep-read passes covering the full
  learning lifecycle, Stage 1's request/response handling, and Planner's context assembly), not
  on an observed request pair. If any of the traced code has since changed, or if OpenAI's
  `file_search` tool behavior around retrieved-content forwarding differs from what the
  Responses API documentation and this codebase's own parsing logic imply, that would need to be
  re-verified.
- **No token-level cost figure is asserted.** Part 12 of the task asked for a token-duplication
  estimate; this report does not provide one, because doing so responsibly requires either real
  captured requests (not obtained, see above) or a token-counting exercise against fabricated
  content, which would violate "do not fabricate savings." The qualitative finding (two separate
  API calls, not one inflated call) stands on code evidence alone.
- **`QueryGovernanceService`-style tenant-loop gaps are a separate, pre-existing concern**
  unrelated to this phase, noted only because it surfaced during investigation of similar
  scheduled/async patterns — not in scope here and not touched.
- **The RESOLUTIONS/LEARNED BUSINESS KNOWLEDGE overlap (§5, Option E) is confirmed structurally
  possible but its actual frequency in production is unmeasured** — no query was run against
  live `nexus_operational_vocabulary`/`nexus_learned_mapping` data to determine how often a
  promoted, concept-classified mapping also has a live vocabulary counterpart still referencing
  the same fact. Treat the overlap as a proven *possibility*, not a measured *rate*.
- **Vector Store staleness (§2) means Stage 1's concept-selection quality can lag Postgres** by
  up to the time between a trigger event and its async/scheduled sync completing (or indefinitely,
  for a promoted-but-unclassified mapping with no `concept_key`). This does not affect Planner's
  block (always fresh, direct Postgres read) but is a pre-existing characteristic of Stage 1
  worth keeping in mind for any future work in this area.
