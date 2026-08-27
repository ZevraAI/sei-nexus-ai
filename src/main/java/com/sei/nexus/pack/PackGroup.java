package com.sei.nexus.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A canonical grouping of Global Business Concepts within an industry pack — e.g.
 * "Procurement" grouping Purchase Order / Supplier / Supplier Contract.
 *
 * <p>Global Pack Foundation: this is the one layer the pack format was missing to
 * represent the approved Industry → Group → Global Business Concept hierarchy. It is
 * purely additive — {@link IndustryPack#groups()} is {@code null} for every pack file
 * authored before this change, and nothing in {@link PackEntityMapper} or
 * {@link IndustryPackService#applyPack} reads it yet; this record only establishes the
 * data shape. {@code groupKey} is stable identity (scoped within this pack, exactly
 * like {@link PackEntity#conceptKey()}); {@code groupName} is the renameable display
 * string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackGroup(
        String           groupKey,
        String           groupName,
        List<PackEntity> concepts
) {}
