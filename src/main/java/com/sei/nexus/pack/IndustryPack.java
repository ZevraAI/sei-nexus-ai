package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * An industry context pack loaded from a classpath JSON resource at startup.
 *
 * <p>Each pack bundles pre-defined entities, vocabulary, suggested questions,
 * KPI definitions, and alert templates for a business vertical.  Packs are
 * industry-generic — a healthcare pack works for a private hospital, an NHS
 * trust, a clinic, or a health insurance company.
 *
 * <p>Pack definitions live in {@code src/main/resources/industry-packs/}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndustryPack(
        String                   packId,
        String                   industry,
        String                   displayName,
        String                   version,
        String                   description,
        List<PackEntity>         entities,
        List<PackVocabularyTerm> vocabulary,
        List<String>             suggestedQuestions,
        List<PackKpiDefinition>  kpiDefinitions,
        List<PackAlertTemplate>  alertTemplates,
        // Integration template fields (null for standard industry packs)
        String                   category,         // "INTEGRATION" flags this as a system template
        String                   system,           // e.g. "ServiceNow"
        String                   icon,             // short display abbreviation e.g. "SN"
        String                   tagline,          // one-line pitch
        List<String>             whatYouCanAsk,    // example questions shown in the UI
        PackAgentDefinition      agentDefinition   // pre-built agent created on apply
) {
    /** True when this pack is a system integration template rather than a generic industry pack. */
    public boolean isIntegration() {
        return "INTEGRATION".equals(category);
    }
}
