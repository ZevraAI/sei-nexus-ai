package com.sei.nexus.agentmemory;

import java.time.Instant;

/**
 * One reference row in a conversation's Business Object roster (Conversation Memory).
 *
 * <p>Deliberately minimal — a REFERENCE only. It carries no physical columns, no
 * descriptions, no relationships, no business guidance, no SQL, and no metadata
 * snapshot. Business World ({@code nexus_data_object}, via {@code EnterpriseMapRepository})
 * remains the sole authoritative source for anything beyond this identity/label pair.
 */
public record ConversationRosterEntry(
        String conversationId,
        String entityKey,
        String businessName,
        String objectType,
        Instant discoveredAt
) {}
