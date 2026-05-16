package com.sei.nexus.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionService.class);

    /**
     * Delete evidence for runs in unpinned conversations older than retention window.
     * Evidence must be deleted first due to FK constraints.
     */
    private static final String DELETE_OLD_EVIDENCE =
            "DELETE FROM nexus_evidence " +
            "WHERE run_key IN (" +
            "  SELECT r.run_key FROM nexus_run r " +
            "  WHERE r.created_at < NOW() - INTERVAL '1 day' * ? " +
            "  AND r.conversation_id NOT IN (" +
            "    SELECT conversation_id FROM nexus_conversation_pin" +
            "  )" +
            ")";

    /**
     * Delete runs in unpinned conversations older than retention window.
     */
    private static final String DELETE_OLD_RUNS =
            "DELETE FROM nexus_run " +
            "WHERE created_at < NOW() - INTERVAL '1 day' * ? " +
            "AND conversation_id NOT IN (" +
            "  SELECT conversation_id FROM nexus_conversation_pin" +
            ")";

    @Value("${nexus.retention.conversation-days:3}")
    private int retentionDays;

    private final JdbcTemplate jdbc;

    public ConversationRetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs daily at 02:00 UTC. Deletes unpinned conversation runs older than the
     * configured retention period. Pinned conversations are kept forever.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @Transactional
    public void purgeOldConversations() {
        log.info("Starting conversation retention purge (retention: {} days)", retentionDays);

        try {
            int evidenceDeleted = jdbc.update(DELETE_OLD_EVIDENCE, retentionDays);
            log.info("Deleted {} evidence records from old conversations", evidenceDeleted);

            int runsDeleted = jdbc.update(DELETE_OLD_RUNS, retentionDays);
            log.info("Deleted {} runs from old unpinned conversations", runsDeleted);

        } catch (Exception e) {
            log.error("Error during conversation retention purge", e);
        }
    }
}
