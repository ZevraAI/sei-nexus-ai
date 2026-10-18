package com.sei.nexus.agentrunner;

import java.time.Instant;

/**
 * A reusable way of investigating one kind of question, owned by an agent. The model sees every
 * ACTIVE playbook of the agent and decides whether one applies; Java never matches on keywords.
 */
public record ZevraPlaybook(
        String  id,
        String  agentId,
        String  tenantSchema,
        String  name,
        String  whenToUse,
        String  steps,
        String  evidenceOrder,
        String  escalation,
        String  status,
        String  createdBy,
        Instant createdAt,
        Instant updatedAt
) {}
