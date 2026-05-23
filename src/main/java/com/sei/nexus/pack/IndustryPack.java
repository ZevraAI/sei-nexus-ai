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
        List<PackAlertTemplate>  alertTemplates
) {}
