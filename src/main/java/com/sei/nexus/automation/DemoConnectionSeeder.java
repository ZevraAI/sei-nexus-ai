package com.sei.nexus.automation;

import com.sei.nexus.connection.ConnectionRepository;
import com.sei.nexus.connection.NexusConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ensures the built-in "nexus-local" connection exists after startup so that
 * the demo automation workflows (order-fulfillment, damage-claim-processor)
 * can query the demo tables without manual setup.
 *
 * The connection is skipped if it already exists, so user edits are preserved.
 */
@Component
public class DemoConnectionSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoConnectionSeeder.class);
    private static final String DEMO_KEY = "local-db";

    private final ConnectionRepository connectionRepository;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DemoConnectionSeeder(
            ConnectionRepository connectionRepository,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        this.connectionRepository = connectionRepository;
        this.jdbcUrl   = jdbcUrl;
        this.username  = username;
        this.password  = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (connectionRepository.findByKey(DEMO_KEY).isPresent()) {
            return;
        }
        try {
            NexusConnection demo = new NexusConnection(
                    DEMO_KEY,
                    "Nexus Local Database",
                    "POSTGRES",
                    "Built-in read-only connection to the Nexus platform database — used by demo automation workflows.",
                    jdbcUrl,
                    null,
                    username,
                    password,
                    "public",    // allowedSchemas
                    null,        // allowedTables
                    true,        // readOnly
                    null, null, null,
                    "ACTIVE",
                    Instant.now(),
                    Instant.now()
            );
            connectionRepository.save(demo);
            log.info("Seeded built-in '{}' database connection for demo workflows", DEMO_KEY);
        } catch (Exception e) {
            log.warn("Could not seed '{}' connection: {}", DEMO_KEY, e.getMessage());
        }
    }
}
