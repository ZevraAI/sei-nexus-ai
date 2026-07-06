package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PackAgentDefinition(
        String name,
        String slug,
        String description,
        String persona,
        String goal,
        int    maxIterations
) {}
