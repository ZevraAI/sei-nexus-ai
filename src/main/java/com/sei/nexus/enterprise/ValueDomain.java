package com.sei.nexus.enterprise;

import java.time.Instant;

/**
 * A discovered (or curated) column value domain, scoped to a connection + source
 * schema. Stored in nexus_value_domain (V034, PRO-10).
 *
 * @param domainValuesJson ordered legal values as a JSON array of strings,
 *                         e.g. ["open","temporarily_closed","closed"]
 * @param source           ENUM today; CHECK / DOMAIN / OBSERVED / MANUAL reserved
 * @param isAuthoritative  true for declared domains (ENUM); false would mark
 *                         sampled/observed domains as advisory only
 */
public record ValueDomain(
        String domainValueKey,
        String connectionKey,
        String sourceSchema,
        String domainName,
        String source,
        boolean isAuthoritative,
        String domainValuesJson,
        Instant scannedAt
) {}
