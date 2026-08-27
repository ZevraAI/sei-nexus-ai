package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A named business entity defined in an industry pack.
 *
 * <p>Global Pack Foundation: {@code conceptKey} and {@code status} are additive —
 * absent in every pack file authored before this change, and tolerated as {@code null}
 * by Jackson (missing JSON properties on a record simply bind to {@code null} for
 * reference types), so all six existing pack JSON files continue parsing unchanged.
 * {@code conceptKey} is the stable Global Business Concept identity <em>within this
 * pack</em> (never globally unique on its own — see {@code IndustryPack.groups}); it
 * is never derived from {@code name}, {@code tablePatterns}, or any physical table
 * name. {@code tablePatterns}/{@code keyColumnPatterns} remain exactly what they were:
 * matching evidence for {@link PackEntityMapper}, never identity.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackEntity(
        String       name,
        List<String> aliases,
        List<String> tablePatterns,       // lowercase substrings to match against table names
        List<String> keyColumnPatterns,   // common primary key / ID column names
        String       description,
        String       operationalMeaning,
        // Stable Global Business Concept identity within this pack — e.g. "purchase_order".
        // Null for entities defined before this field existed; never guessed from `name`.
        String       conceptKey,
        // ACTIVE | DEPRECATED. Null/absent is treated as ACTIVE by every current reader —
        // no pack file needs this field for existing behavior to continue unchanged.
        String       status
) {}
