package com.sei.nexus.artifacts;

import com.sei.nexus.response.StructuredAnswer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds {@link ResponseArtifacts} from what an execution path already produced — no new LLM
 * calls, no fabricated content. Stateless, package-visible static methods so this can be
 * unit-tested directly (this repo's convention: hand-rolled fakes, no Mockito, no Spring
 * context).
 *
 * <p><b>Semantic fields (understanding / keyFindings / relatedFacts / recommendation /
 * followUpQuestions) are LLM-authored, not Java-derived, whenever {@code llmSemantics} is
 * present</b> —
 * see {@code StructuredAnswer} and {@code ChatService.DATA_ANSWER_JSON_SYSTEM_PROMPT} /
 * {@code AgentToolRegistry}'s {@code final_answer} schema. The regex/sentence-splitting methods
 * below (originally moved server-side from a client-side heuristic) now run ONLY as a legacy
 * fallback — for a response that never went through structured composition (an older code path,
 * or a non-data outcome with nothing to decompose). Once structured composition exists for a
 * response, this builder does not re-derive or blend with it; the model's own decomposition
 * wins outright, including a legitimate empty section (the model deciding a field doesn't apply
 * is real information, not a gap to fill heuristically).
 *
 * <p>Evidence and trail remain 100% deterministic/runtime-owned regardless of {@code
 * llmSemantics} — those are computed directly from {@code queryData}/{@code reasoningSteps},
 * never from answer text, and the model has no say in them. That split is the architectural line
 * Zevra draws between reasoning (the model) and runtime (validated, computational facts about the
 * evidence).
 *
 * <p><b>Metrics are the one exception to that split</b> — see {@link #metrics}: they are the model's
 * own whole-answer headline figures (declared alongside {@code sections} in the JSON contract),
 * relayed verbatim. Java computes none of its own: a row count or column total taken from whichever
 * dataset happened to be selected says nothing about what the user asked, so no model metrics
 * means no tiles.
 */
public final class ResponseArtifactsBuilder {

    private ResponseArtifactsBuilder() {}

    public static ResponseArtifacts build(String question, String answer,
            List<Map<String, Object>> reasoningSteps, List<Map<String, Object>> queryData,
            List<Map<String, Object>> investigationDatasets,
            List<Map<String, Object>> quickRefinements, ResponseArtifacts.AgentContext agentContext,
            StructuredAnswer llmSemantics) {
        return build(question, answer, reasoningSteps, queryData, investigationDatasets,
                quickRefinements, agentContext, llmSemantics, List.of());
    }

    /**
     * As the 8-arg {@link #build}, with one additive parameter: {@code resolvedSections} — the
     * model's UI-content plan (see {@code StructuredAnswer.Section}), already resolved against
     * the investigation's real datasets (see {@code ChatService#resolveSections}). This method
     * performs no resolution, selection, or interpretation of its own — {@code resolvedSections}
     * is carried straight into {@link ResponseArtifacts#sections} unchanged.
     */
    public static ResponseArtifacts build(String question, String answer,
            List<Map<String, Object>> reasoningSteps, List<Map<String, Object>> queryData,
            List<Map<String, Object>> investigationDatasets,
            List<Map<String, Object>> quickRefinements, ResponseArtifacts.AgentContext agentContext,
            StructuredAnswer llmSemantics, List<ResponseArtifacts.Section> resolvedSections) {

        String understanding;
        List<String> keyFindings;
        List<String> relatedFacts;
        String recommendation;
        List<ResponseArtifacts.Recommendation> followUpQuestions;

        if (llmSemantics != null) {
            // LLM-authored — preferred outright. A legitimately empty field here means the model
            // decided that section didn't apply to this question, not that extraction failed.
            understanding = blankToNull(llmSemantics.understanding());
            keyFindings = safeList(llmSemantics.keyFindings());
            relatedFacts = safeList(llmSemantics.relatedFacts());
            recommendation = blankToNull(llmSemantics.recommendation());
            followUpQuestions = toRecommendations(llmSemantics.followUpQuestions());
        } else {
            // Legacy fallback only — this response never went through structured composition.
            List<String> sentences = sentences(answer);
            understanding = understanding(question, answer);
            keyFindings = keyFindings(sentences, reasoningSteps);
            recommendation = recommendation(sentences);
            relatedFacts = relatedFacts(sentences, keyFindings, understanding, recommendation);
            followUpQuestions = List.of();
        }

        // Tactical UI actions (decision-type-driven, e.g. "Show exceptions only") are a SEPARATE
        // concept from the model's own FOLLOW_UP_QUESTIONS decision — never merged into
        // `followUpQuestions` here, even when the model's own list is empty. An empty
        // `followUpQuestions` is an honest signal ("the model decided no follow-up question is
        // warranted, or produced none") that must reach the frontend as-is; substituting Java's
        // canned quickRefinements into this same field would make Java the author of a semantic
        // decision that belongs to Agent Brain alone. `quickRefinements` is transported
        // separately (see ChatResponse#quickRefinements) for the frontend's own, clearly separate
        // tactical-actions surface.

        return new ResponseArtifacts(
                understanding,
                keyFindings,
                relatedFacts,
                recommendation,
                followUpQuestions,
                resolvedSections != null ? resolvedSections : List.of(),
                evidence(queryData, investigationDatasets),
                metrics(llmSemantics),
                trail(reasoningSteps),
                agentContext);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static List<String> safeList(List<String> list) {
        if (list == null) return List.of();
        return list.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private static List<ResponseArtifacts.Recommendation> toRecommendations(List<String> steps) {
        if (steps == null || steps.isEmpty()) return List.of();
        return steps.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> new ResponseArtifacts.Recommendation(s, s))
                .toList();
    }

    // ── Understanding — the answer's opening statement. Same heuristic as
    // ChatService.findingTitle (duplicated, not extracted, to keep this package independent of
    // ChatService's private surface — both are tiny, pure, and now each individually tested). ──
    private static String understanding(String question, String answer) {
        if (answer == null || answer.isBlank()) return null;
        String t = answer.trim().replaceAll("\\s+", " ");
        int dot = t.indexOf(". ");
        String first = dot > 15 ? t.substring(0, dot + 1) : (t.length() <= 120 ? t : null);
        if (first != null) {
            String fl = first.toLowerCase(Locale.ROOT);
            boolean listIntro = first.endsWith(":") || fl.contains("as follows")
                    || fl.contains("are:") || fl.contains("the following");
            if (!listIntro && first.length() <= 140) return first;
        }
        String q = question == null ? null : question.trim();
        if (q == null || q.isEmpty()) return null;
        return q.length() <= 140 ? q : q.substring(0, 140).trim() + "…";
    }

    // ── Sentence splitting — same shape as the frontend's toSentences(): list-item lines stay
    // atomic, everything else splits on sentence boundaries. ────────────────────────────────────
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\d+[.)]|[-*•])\\s+");
    private static final Pattern LIST_INTRO = Pattern.compile(
            "[:;]$|^(here (are|is)|the most critical|these are|below (are|is)|following)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_IMPACT = Pattern.compile(
            "\\$[\\d,]{3,}|\\b\\d+(\\.\\d+)?%\\s*(rise|increase|drop|decrease|decline|deviation|surge|higher|lower)"
                    + "|\\blost sales\\b|\\bpotential (stockout|loss)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_RECO = Pattern.compile(
            "\\b(recommend|prioriti[sz]e|consider|suggest|advis|renegotiat|you should|should be)\\b",
            Pattern.CASE_INSENSITIVE);

    private static List<String> sentences(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : text.replaceAll("[ \\t]+", " ").split("\\n+")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (LIST_ITEM.matcher(line).find()) {
                out.add(line);
                continue;
            }
            for (String s : line.split("(?<=[.!?])\\s+")) {
                s = s.trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    private static boolean isListy(String s) {
        return LIST_ITEM.matcher(s).find() || LIST_INTRO.matcher(s).find();
    }

    // ── Key findings — a genuine quantified-impact sentence from the answer (absent when the
    // answer doesn't state one), plus the evaluator's own rationale whenever it flagged
    // something noteworthy while querying (any decision other than a clean SUFFICIENT) — the
    // evaluator already made this judgment; this only surfaces it. ─────────────────────────────
    private static List<String> keyFindings(List<String> sentences, List<Map<String, Object>> reasoningSteps) {
        List<String> out = new ArrayList<>();
        for (String s : sentences) {
            if (s.length() >= 24 && !isListy(s) && RE_IMPACT.matcher(s).find()) {
                out.add(s);
                break;
            }
        }
        if (reasoningSteps != null) {
            for (Map<String, Object> step : reasoningSteps) {
                Object decision = step.get("evaluatorDecision");
                Object rationale = step.get("evaluatorRationale");
                if (rationale != null && !String.valueOf(rationale).isBlank()
                        && decision != null && !"SUFFICIENT".equals(decision)) {
                    out.add(String.valueOf(rationale));
                    break;
                }
            }
        }
        return out.stream().distinct().limit(3).toList();
    }

    private static String recommendation(List<String> sentences) {
        for (String s : sentences) {
            if (s.length() >= 24 && !isListy(s) && RE_RECO.matcher(s).find()) return s;
        }
        return null;
    }

    // ── Related facts — remaining declarative sentences not already used as understanding, a
    // key finding, or the recommendation. Same "leftover sentence" technique already shipped on
    // the frontend, moved here so the Zevra Agent path (which never had this) gets it too. ─────
    private static List<String> relatedFacts(List<String> sentences, List<String> keyFindings,
            String understanding, String recommendation) {
        List<String> used = new ArrayList<>(keyFindings);
        if (understanding != null) used.add(understanding);
        if (recommendation != null) used.add(recommendation);
        List<String> out = new ArrayList<>();
        for (String s : sentences) {
            if (isListy(s)) continue;
            boolean overlaps = used.stream().anyMatch(u -> s.equals(u) || u.contains(s) || s.contains(u));
            if (!overlaps) out.add(s);
            if (out.size() == 3) break;
        }
        return out;
    }

    private static List<ResponseArtifacts.Recommendation> followUpQuestions(List<Map<String, Object>> quickRefinements) {
        if (quickRefinements == null || quickRefinements.isEmpty()) return List.of();
        List<ResponseArtifacts.Recommendation> out = new ArrayList<>();
        for (Map<String, Object> r : quickRefinements) {
            Object label = r.get("label");
            Object prompt = r.get("prompt");
            if (label != null && prompt != null) {
                out.add(new ResponseArtifacts.Recommendation(String.valueOf(label), String.valueOf(prompt)));
            }
        }
        return out;
    }

    // ── Evidence ─────────────────────────────────────────────────────────────────────────────
    // Two chart-hint tiers, in preference order:
    //   1. LLM-declared, per-dataset (see ReasoningPlanner.StepPlan's OPTIONAL VISUALIZATION
    //      HINT guidance): the model itself chose chartType/categoryKey/valueKeys for that
    //      step's own result when it built the query. Java's ONLY check here is mechanical —
    //      do the referenced column name(s) actually exist as keys in that step's own rows?
    //      Never a semantic/shape judgment. A hint referencing an absent column is silently
    //      dropped for that one dataset (no CHART entry emitted for it) — never a crash, never
    //      a fallback substitution of Java's own guess in its place.
    //   2. The pre-existing Java-side shape heuristic below (numeric/date/categorical detection,
    //      mirroring DataViz.jsx's client-side selectConfig) — kept EXACTLY as before, as the
    //      fallback tier, scoped to the single legacy `queryData` blob only (never applied per
    //      investigation dataset) — unchanged behavior for any un-hinted/legacy response.
    //   1. single row, ≥1 numeric        → stat cards
    //   2. date + numeric, 2–200 rows    → area chart
    //   3. categorical + numeric, 2–30   → bar chart (x = lowest-cardinality categorical column)
    //   4. otherwise                     → dataset only, no chart hint
    private static final Pattern DATE_RE = Pattern.compile(
            "^\\d{4}-\\d{2}(-\\d{2})?$|^\\d{2}/\\d{2}/\\d{4}$|^\\d{4}/\\d{2}/\\d{2}$");

    private static List<ResponseArtifacts.Evidence> evidence(List<Map<String, Object>> queryData,
            List<Map<String, Object>> investigationDatasets) {
        List<ResponseArtifacts.Evidence> out = new ArrayList<>();

        // One DATASET entry per row-bearing investigation step — mechanical, in step order, no
        // ranking/merging/selection among them. Preferred outright over the single legacy
        // `queryData`-sized entry below whenever the investigation produced this richer,
        // per-step representation (see InvestigationDataset). Label/description are copied
        // verbatim from what the step already recorded — never re-derived from row content.
        if (investigationDatasets != null && !investigationDatasets.isEmpty()) {
            for (Map<String, Object> ds : investigationDatasets) {
                Object stepNoObj = ds.get("stepNo");
                Integer stepNo = stepNoObj instanceof Number num ? num.intValue() : null;
                Object description = ds.get("description");
                String label = (description == null || String.valueOf(description).isBlank())
                        ? "Step " + stepNoObj
                        : "Step " + stepNoObj + ": " + description;
                List<Map<String, Object>> dsRows = asRowList(ds.get("rows"));
                out.add(new ResponseArtifacts.Evidence("DATASET", label, null, null, null, dsRows.size(), stepNo));

                // LLM-declared per-dataset chart hint — see the class-level comment above. The
                // ONLY validation performed is the existence check below; chartType/categoryKey/
                // valueKeys themselves are relayed exactly as the model declared them.
                String hintChartType = strOrNull(ds.get("chartType"));
                if (hintChartType != null && !dsRows.isEmpty()) {
                    Set<String> keys = dsRows.get(0).keySet();
                    String categoryKey = strOrNull(ds.get("categoryKey"));
                    List<String> rawValueKeys = stringList(ds.get("valueKeys"));
                    List<String> valueKeys = rawValueKeys.stream().filter(keys::contains).toList();
                    boolean categoryOk = "stats".equals(hintChartType)
                            || (categoryKey != null && keys.contains(categoryKey));
                    if (categoryOk && !valueKeys.isEmpty()) {
                        // Optional, purely presentational LLM-authored labels (see ReasoningPlanner.
                        // SYSTEM_PROMPT's OPTIONAL HUMAN-READABLE LABELS guidance) — relayed
                        // verbatim. A mechanical safety check only: yLabels is used ONLY when every
                        // declared value_key survived the existence filter above AND the label list
                        // is the same length, so a label never ends up paired with the wrong key.
                        String xLabel = "stats".equals(hintChartType) ? null : strOrNull(ds.get("categoryLabel"));
                        List<String> rawValueLabels = stringListPreserveBlanks(ds.get("valueLabels"));
                        List<String> yLabels = (rawValueKeys.size() == valueKeys.size()
                                && rawValueLabels.size() == rawValueKeys.size())
                                ? rawValueLabels : List.of();
                        out.add(new ResponseArtifacts.Evidence("CHART", label, hintChartType,
                                "stats".equals(hintChartType) ? null : categoryKey,
                                valueKeys, dsRows.size(), stepNo, xLabel, yLabels));
                    }
                    // else: hint referenced a column absent from this step's own rows — silently
                    // no CHART entry for this dataset (no Java-side substitution/guess).
                }
            }
        } else if (queryData != null && !queryData.isEmpty()) {
            // Legacy fallback — no per-step representation was supplied; describe the single
            // dataset exactly as before this change.
            out.add(new ResponseArtifacts.Evidence("DATASET", "Dataset", null, null, null, queryData.size(), null));
        }

        if (queryData == null || queryData.isEmpty()) return out;

        // Fallback tier 2 — chart hint selection scoped to the single legacy `queryData` (the
        // "primary visualisation" dataset) — unchanged by this fix; chart selection across
        // multiple heterogeneous datasets is handled by the per-dataset hint loop above.
        Map<String, String> types = columnTypes(queryData);
        List<String> numeric = types.entrySet().stream()
                .filter(e -> "numeric".equals(e.getValue())).map(Map.Entry::getKey).toList();
        List<String> dates = types.entrySet().stream()
                .filter(e -> "date".equals(e.getValue())).map(Map.Entry::getKey).toList();
        List<String> categorical = types.entrySet().stream()
                .filter(e -> "categorical".equals(e.getValue())).map(Map.Entry::getKey).toList();
        int n = queryData.size();

        if (n == 1 && !numeric.isEmpty()) {
            out.add(new ResponseArtifacts.Evidence("CHART", "Key metrics", "stats", null, numeric, n, null));
        } else if (!dates.isEmpty() && n >= 2 && n <= 200) {
            out.add(new ResponseArtifacts.Evidence("CHART", "Trend over time", "area", dates.get(0),
                    numeric.stream().limit(3).toList(), n, null));
        } else if (!categorical.isEmpty() && !numeric.isEmpty() && n >= 2 && n <= 30) {
            String xKey = categorical.stream()
                    .min(Comparator.comparingInt(c -> uniqueCount(queryData, c)))
                    .orElse(categorical.get(0));
            out.add(new ResponseArtifacts.Evidence("CHART", "Distribution", "bar", xKey,
                    numeric.stream().limit(2).toList(), n, null));
        }
        return out;
    }

    /** Defensive conversion of a raw {@code Object} (from the investigationDatasets map) into a
     *  {@code List<Map<String,Object>>} — never throws on an unexpected shape. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRowList(Object rows) {
        if (!(rows instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?>) out.add((Map<String, Object>) o);
        return out;
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    /** Defensive conversion of a raw {@code Object} (from the investigationDatasets map) into a
     *  {@code List<String>} — never throws on an unexpected shape. */
    private static List<String> stringList(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o));
        return out;
    }

    /** As {@link #stringList}, but preserves position (a blank/null entry becomes {@code ""}
     *  rather than being dropped) — required for a list like {@code valueLabels} whose entries
     *  must stay index-aligned with a parallel list (e.g. {@code valueKeys}). */
    private static List<String> stringListPreserveBlanks(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) out.add(o == null ? "" : String.valueOf(o));
        return out;
    }

    private static Map<String, String> columnTypes(List<Map<String, Object>> rows) {
        Map<String, String> types = new LinkedHashMap<>();
        for (String col : rows.get(0).keySet()) {
            types.put(col, detectType(rows, col));
        }
        return types;
    }

    private static String detectType(List<Map<String, Object>> rows, String col) {
        List<Object> vals = rows.stream().map(r -> r.get(col)).filter(v -> v != null && !"".equals(v)).toList();
        if (vals.isEmpty()) return "null";
        if (vals.stream().allMatch(ResponseArtifactsBuilder::isNumeric)) return "numeric";
        if (vals.stream().allMatch(v -> DATE_RE.matcher(String.valueOf(v)).matches())) return "date";
        return "categorical";
    }

    private static boolean isNumeric(Object v) {
        if (v instanceof Number) return true;
        try {
            Double.parseDouble(String.valueOf(v));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int uniqueCount(List<Map<String, Object>> rows, String col) {
        Set<String> set = new HashSet<>();
        for (Map<String, Object> r : rows) set.add(String.valueOf(r.get(col)));
        return set.size();
    }

    // ── Metrics — the model's own whole-answer headline figures (see ChatService.DATA_ANSWER_JSON_
    // SYSTEM_PROMPT's METRICS rules), relayed verbatim. Java adds none of its own: a row count or a
    // column total computed from whichever dataset happened to be selected says nothing about what
    // the user asked (it once read "1 Result" above an answer of "no such records", because the only
    // rows were a list of values used to verify that). No model metrics means no tiles. ───────────
    private static List<ResponseArtifacts.Metric> metrics(StructuredAnswer llmSemantics) {
        if (llmSemantics == null || llmSemantics.metrics() == null) return List.of();
        return llmSemantics.metrics().stream()
                .map(m -> new ResponseArtifacts.Metric(m.label(), m.value()))
                .toList();
    }

    // ── Trail — normalizes the two independently-shaped reasoningSteps sources (conversational:
    // resolution / literal / plain SQL-step maps; Zevra Agent: CONTEXT_RESOLVE / TOOL_CALL /
    // FINAL_ANSWER maps already projected by ChatService.agentReasoningSteps) into one shared
    // shape. Every entry is read straight from what the execution path already recorded —
    // nothing computed here. ────────────────────────────────────────────────────────────────────
    private static List<ResponseArtifacts.TrailStep> trail(List<Map<String, Object>> reasoningSteps) {
        if (reasoningSteps == null || reasoningSteps.isEmpty()) return List.of();
        List<ResponseArtifacts.TrailStep> out = new ArrayList<>();
        for (Map<String, Object> step : reasoningSteps) {
            Object typeObj = step.get("type");
            String type = typeObj == null ? "SQL_STEP" : String.valueOf(typeObj).toUpperCase(Locale.ROOT);
            if ("CONTEXT_RESOLVE".equals(type)) type = "RESOLUTION";
            String label = String.valueOf(step.getOrDefault("description", ""));
            String detail = step.get("sql") != null ? String.valueOf(step.get("sql")) : null;
            // Investigation-Step Semantics: "outcome" (this step's own result — a successful
            // query/metadata retrieval, or a genuine decline/rejection/error) is the correct,
            // primary status for a trail step, and takes precedence when present. "evaluatorDecision"
            // (the evaluator's separate verdict on whether the OVERALL accumulated evidence is
            // sufficient — e.g. "NEED_MORE_DATA") is only a fallback, for any caller that has not
            // (yet) supplied "outcome" — it must never overwrite a step's own success status just
            // because reasoning continued past it.
            Object outcomeVal = step.get("outcome");
            String outcome = (outcomeVal != null && !String.valueOf(outcomeVal).isBlank())
                    ? String.valueOf(outcomeVal)
                    : step.get("evaluatorDecision") != null ? String.valueOf(step.get("evaluatorDecision")) : null;
            out.add(new ResponseArtifacts.TrailStep(type, label, detail, outcome));
        }
        return out;
    }
}
