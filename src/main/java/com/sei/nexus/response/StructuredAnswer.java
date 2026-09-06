package com.sei.nexus.response;

import java.util.ArrayList;
import java.util.List;

/**
 * What the answer-composition LLM call returns when asked for structured output (JSON mode) —
 * the model's own decomposition of its answer into semantic roles, not a Java-derived
 * approximation of one. Every field beyond {@code answer} is optional: the model is instructed
 * to return null/empty for a section that doesn't add value for this particular question,
 * rather than manufacturing content to fill the schema.
 *
 * <p>This is the ownership boundary Zevra draws between reasoning and runtime: the model decides
 * what it understood, what stands out in the evidence, what's related, what to recommend, and
 * what to investigate next — Java only validates the shape and, on any parse failure, falls back
 * to {@code answer} alone (see {@code ChatService.parseStructuredAnswer}). Java never infers
 * these fields from the answer text itself; that heuristic path exists only as a legacy fallback
 * for responses that never went through structured composition (see
 * {@code ResponseArtifactsBuilder}).
 *
 * <p><b>UI-content planning ({@code sections}):</b> the model's primary output going forward is
 * {@code answer} plus {@code sections} — a UI-content plan the model itself authors, deciding
 * which investigation dataset (by its Java-assigned {@code step-N} identifier) answers which
 * part of the question, which dataset(s) should be displayed, and what belongs in findings /
 * related facts / recommendation / next steps. The five legacy flat fields below are never
 * independently populated by the model once {@code sections} is used — see {@link
 * #fromSections} — they exist only so existing consumers that read them directly keep working
 * unchanged, via a mechanical, type-tag-driven projection Java performs from {@code sections}.
 * Java never re-derives these from prose, never selects a dataset, and never decides placement —
 * it only copies fields the model already tagged by {@code type}.
 *
 * @param answer         the primary answer, in prose — always present.
 * @param sections       the model's own UI-content plan — see class javadoc. Empty for a
 *                        response that never went through structured composition, or for the
 *                        Zevra Agent path (a separate composition mechanism; see {@code
 *                        AgentToolRegistry}'s {@code final_answer} schema), which does not
 *                        currently populate this field.
 * @param understanding  Zevra's own paraphrase of what it found; null when {@code answer} is
 *                        already a single self-contained statement not worth restating. Always
 *                        null when derived via {@link #fromSections} — {@code answer} is the
 *                        narrative in the sections-based contract; there is no UNDERSTANDING
 *                        section type.
 * @param keyFindings    genuine, materially significant discoveries from the evidence; empty
 *                        when nothing stands out beyond the answer itself.
 * @param relatedFacts   additional context that helps explain the finding, distinct from both
 *                        {@code understanding} and {@code keyFindings}; empty when there is none.
 * @param recommendation what the business should consider doing, grounded in the evidence; null
 *                        when no recommendation is warranted.
 * @param nextSteps      concrete follow-up investigations the user could ask next, specific to
 *                        this question/evidence — not generic filler; empty when none apply.
 */
public record StructuredAnswer(
        String answer,
        List<Section> sections,
        String understanding,
        List<String> keyFindings,
        List<String> relatedFacts,
        String recommendation,
        List<String> nextSteps
) {
    /** Pre-{@code sections} shape — every pre-existing caller/constructor. {@code sections}
     *  defaults to empty; behavior is otherwise byte-identical to before this field existed. */
    public StructuredAnswer(String answer, String understanding, List<String> keyFindings,
                            List<String> relatedFacts, String recommendation, List<String> nextSteps) {
        this(answer, List.of(), understanding, keyFindings, relatedFacts, recommendation, nextSteps);
    }

    /** A structured answer carrying no semantic decomposition — the legacy-compatible shape.
     *  Byte-identical to before {@code sections} existed (every field null) except {@code
     *  sections} itself, which is empty (never null) — a new field, nothing to be compatible
     *  with. */
    public static StructuredAnswer plain(String answer) {
        return new StructuredAnswer(answer, List.of(), null, null, null, null, null);
    }

    /**
     * Builds a {@link StructuredAnswer} from the model's UI-content plan — the sections-based
     * contract's sole construction path. The legacy flat fields are derived here by a purely
     * mechanical, type-tag-driven projection (concatenate every section's {@code items}/{@code
     * content} whose {@code type} matches) — never a re-interpretation of content, never a
     * decision about relevance or placement. That decision was already made by the model when
     * it chose each section's {@code type}; this only copies what it already labeled.
     *
     * <p>{@code understanding} is always {@code null} here — there is no UNDERSTANDING section
     * type in this contract; {@code answer} is the narrative.
     */
    public static StructuredAnswer fromSections(String answer, List<Section> sections) {
        List<Section> s = sections == null ? List.of() : sections;
        // A HIGHLIGHT's content is a distinguished, dataset-grounded observation — mechanically
        // the same kind of content as a FINDINGS item, just additionally traceable to a dataset.
        // Type-tag-driven only: appended by `type`, never by inspecting what the content says.
        List<String> keyFindings = new ArrayList<>(itemsOfType(s, "FINDINGS"));
        keyFindings.addAll(contentsOfType(s, "HIGHLIGHT"));
        return new StructuredAnswer(answer, s, null,
                keyFindings, itemsOfType(s, "RELATED_FACTS"),
                firstContentOfType(s, "RECOMMENDATION"), itemsOfType(s, "NEXT_STEPS"));
    }

    private static List<String> contentsOfType(List<Section> sections, String type) {
        List<String> out = new ArrayList<>();
        for (Section sec : sections) {
            if (type.equals(sec.type()) && sec.content() != null && !sec.content().isBlank()) {
                out.add(sec.content());
            }
        }
        return out;
    }

    private static List<String> itemsOfType(List<Section> sections, String type) {
        List<String> out = new ArrayList<>();
        for (Section sec : sections) {
            if (type.equals(sec.type()) && sec.items() != null) out.addAll(sec.items());
        }
        return out;
    }

    private static String firstContentOfType(List<Section> sections, String type) {
        for (Section sec : sections) {
            if (type.equals(sec.type()) && sec.content() != null && !sec.content().isBlank()) {
                return sec.content();
            }
        }
        return null;
    }

    /**
     * One entry in the model's UI-content plan (see class javadoc). A single flat shape covers
     * every kind — unused fields are null/empty, the same "optional beyond the minimum"
     * convention {@link StructuredAnswer} itself already uses.
     *
     * <p><b>Data vs. presentation, and multi-dataset provenance:</b> {@code datasetRefs} is not
     * exclusive to {@code type=DATASET} — any section may carry it, to ground its narrative in
     * the real, execution-produced dataset(s) it actually draws from, rather than leaving a
     * factual claim unverifiable. It is a LIST because a single claim can legitimately depend on
     * more than one dataset — e.g. a {@code HIGHLIGHT} whose name/SKU come from one dataset and
     * whose quantity comes from another must declare both: {@code content} is the model's
     * narrative ("Widget A (SKU ABC-123) is the most ordered item with 1,500 units ordered."),
     * and {@code datasetRefs} names every dataset ({@code ["step-3", "step-5"]}) that narrative's
     * facts come from — never just the one with "the more descriptive portion." One dataset is
     * equally valid ({@code ["step-1"]}); the model decides the cardinality, never Java.
     *
     * <p>Java resolves every listed reference and attaches each dataset's actual rows, under its
     * own step identity, alongside the narrative — see {@code ChatService#resolveSections} — so
     * the UI/audit trail can show what the claim is backed by, without merging the datasets into
     * one. If ANY listed reference doesn't resolve, the ENTIRE section (narrative included) is
     * dropped as a model-contract defect — never partially accepted, never repaired by dropping
     * just the bad reference: a claim citing one real and one fake dataset is exactly as
     * unverifiable as one citing only a fake dataset.
     *
     * @param type        one of {@code DATASET | HIGHLIGHT | FINDINGS | RELATED_FACTS |
     *                    RECOMMENDATION | NEXT_STEPS | TEXT} — the model's own semantic
     *                    classification of this section. Not a Java enum: an unrecognized value
     *                    is passed through unresolved (see {@code resolveSections}), never coerced.
     * @param title       the model's own label for this section, e.g. "Open Purchase Orders".
     * @param purpose     the model's own one-line explanation of what this section shows and why.
     * @param datasetRefs every {@code step-N} identifier (from the investigation context shown to
     *                    the model) this section is grounded in/displays — required (at least one)
     *                    for {@code type=DATASET}, optional (for traceability, and may name more
     *                    than one) on any other type, empty/null when a section has no grounding
     *                    dataset (e.g. a cross-cutting {@code RECOMMENDATION}). Every entry must
     *                    match a dataset actually supplied — never invented. Resolved by exact
     *                    string match only, one at a time — Java performs no fuzzy matching,
     *                    ranking, substitution, or inference about how the datasets relate.
     * @param display     {@code type=DATASET} only: the model's own judgment of whether this
     *                    dataset should be shown to the user. Null defaults to {@code true}.
     * @param items       {@code type=FINDINGS | RELATED_FACTS | NEXT_STEPS}: the section's list
     *                    content.
     * @param content     {@code type=HIGHLIGHT | RECOMMENDATION | TEXT}: the section's prose
     *                    content.
     */
    public record Section(String type, String title, String purpose, List<String> datasetRefs,
                           Boolean display, List<String> items, String content) {}
}
