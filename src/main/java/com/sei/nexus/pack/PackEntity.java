package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A named business entity defined in an industry pack. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackEntity(
        String       name,
        List<String> aliases,
        List<String> tablePatterns,       // lowercase substrings to match against table names
        List<String> keyColumnPatterns,   // common primary key / ID column names
        String       description,
        String       operationalMeaning
) {}
