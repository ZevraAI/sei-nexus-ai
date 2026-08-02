package com.sei.nexus.semanticmodel;

import java.util.List;

/**
 * The primary business abstraction (ADR-0003 semantic model, Phase 1A): a coherent
 * entity a user reasons about (e.g. "Inventory Balance"), composed of its
 * {@link BusinessAttribute}s.
 *
 * <p>It carries <b>no physical identifiers</b> (no schema/table); its physical anchor
 * lives in the execution plane, keyed by {@link #objectKey()}. Measures / dimensions /
 * identifiers are views over {@link #attributes()} filtered by {@link AttributeRole},
 * not separate lists.
 *
 * @param objectKey     immutable semantic identity — never the display name (Invariant 1)
 * @param businessName  human business name
 * @param purpose       business purpose/description (identity aid; {@code null} when unknown)
 * @param guidance      business usage guidance (semantic)
 * @param attributes    the object's business attributes (each belongs to this object only)
 * @param relationships relationships to other objects (empty until Phase 3)
 */
public record BusinessObject(
        String                  objectKey,
        String                  businessName,
        String                  purpose,
        String                  guidance,
        List<BusinessAttribute> attributes,
        List<Relationship>      relationships
) {
    public BusinessObject {
        attributes    = List.copyOf(attributes);
        relationships = List.copyOf(relationships);
    }

    /** Convenience: an object without a distinct business purpose. */
    public BusinessObject(String objectKey, String businessName, String guidance,
                          List<BusinessAttribute> attributes, List<Relationship> relationships) {
        this(objectKey, businessName, null, guidance, attributes, relationships);
    }
}
