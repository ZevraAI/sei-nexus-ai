-- V045: per-call cost observability — cached token counts + fine-grained call type
-- Additive only: existing nexus_usage_event rows and every existing column stay valid.
--
-- cached_tokens: AzureOpenAiClient already reads OpenAI's own
-- prompt_tokens_details.cached_tokens / input_tokens_details.cached_tokens and uses it to
-- apply the discounted cached-input rate in UsageService.record(), but the raw count was
-- never persisted — this closes that gap. Existing rows get 0 (honest "unknown" for
-- historical data, not a claim that nothing was cached).
--
-- call_type: the existing LlmCallTag mechanism (set by each call site immediately before
-- invoking AzureOpenAiClient, e.g. "PLANNER", "EVALUATOR", "ANSWER_COMPOSER",
-- "STAGE1_FILE_SEARCH_CONCEPT_AND_ROUTING", "MEMORY_SELECTION") already exists purely for
-- LLM_METRIC log-line attribution; this persists that same tag as a queryable column,
-- finer-grained than the existing 5-value `feature` enum. NULL for existing rows (their
-- real call type was never captured) and for any future caller that doesn't set a tag —
-- never inferred from feature/model/prompt text.
SET search_path = public;

ALTER TABLE public.nexus_usage_event
    ADD COLUMN IF NOT EXISTS cached_tokens INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS call_type     TEXT;

CREATE INDEX IF NOT EXISTS idx_usage_call_type
    ON public.nexus_usage_event(call_type, created_at DESC);
