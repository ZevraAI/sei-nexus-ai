package com.sei.nexus.governance;

import java.time.Instant;

/**
 * Row-Level Security policy for a data object.
 *
 * <p>When a query touches the associated table, the resolved
 * {@code filterTemplate} is appended as a WHERE (or AND) condition so users
 * only see rows that belong to them.
 *
 * <p>Template placeholder syntax — resolved at query time:
 * <pre>
 *   {user.email}        → authenticated user's email address
 *   {user.role}         → authenticated user's role
 *   {user.region}       → value of "region" key in user's attributes JSON
 *   {user.department}   → value of "department" key in user's attributes JSON
 *   (any {user.<key>}   → looked up in nexus_user_account.attributes)
 * </pre>
 *
 * <p>Example filter templates:
 * <pre>
 *   region_code = {user.region}
 *   (department_id = {user.department} OR is_shared = TRUE)
 *   assigned_to = {user.email}
 * </pre>
 */
public record RlsPolicy(
        String   policyKey,
        String   policyName,
        String   objectKey,
        String   filterTemplate,
        String[] appliesToRoles,   // empty = applies to ALL roles
        boolean  isActive,
        String   createdBy,
        Instant  createdAt,
        Instant  updatedAt
) {}
