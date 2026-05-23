package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A business vocabulary term with its SQL equivalent hint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackVocabularyTerm(
        String       term,
        List<String> aliases,
        String       definition,
        String       sqlHint    // not enforced SQL; injected as a hint to the LLM planner
) {}
