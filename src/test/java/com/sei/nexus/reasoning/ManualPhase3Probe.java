package com.sei.nexus.reasoning;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** TEMPORARY — read-only re-check of current relationship/binding state after the manual
 *  "Discover from DB" run, plus fresh meaning/purpose text for the objects needed for
 *  the Phase 3 report. No metadata changed. */
public class ManualPhase3Probe {

    @Test
    void probe() throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(".env.local"))) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.trim().startsWith("#")) {
                env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        String jdbcUrl = env.get("NEXUS_DB_URL");
        String user = env.get("NEXUS_DB_USERNAME");
        String pass = env.get("NEXUS_DB_PASSWORD");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            try (Statement s = conn.createStatement()) {
                s.execute("SET search_path TO tenant_maryland_corporations, public");
            }

            System.out.println("=====Current binding status: purchase-orders / purchase-order-lines=====");
            query(conn, "SELECT entity_key, primary_object_key FROM nexus_business_entity " +
                    "WHERE entity_key IN ('purchase-orders','purchase-order-lines','product','products')");

            System.out.println("=====Current nexus_entity_relationship rows involving purchase-orders/purchase-order-lines=====");
            query(conn, "SELECT source_entity_key, target_entity_key, relationship_type, source_column, target_column, cardinality, join_guidance " +
                    "FROM nexus_entity_relationship WHERE source_entity_key ILIKE '%purchase-order%' OR target_entity_key ILIKE '%purchase-order%' ORDER BY source_entity_key");

            System.out.println("=====Total nexus_entity_relationship row count now=====");
            query(conn, "SELECT count(*) FROM nexus_entity_relationship");

            System.out.println("=====operational_meaning/investigation_hints for the 7 objects (fresh)=====");
            query(conn, "SELECT entity_key, primary_object_key, operational_meaning FROM nexus_business_entity " +
                    "WHERE entity_key IN ('product','product-category','supplier','purchase-orders','purchase-order-lines','warehouse','inventory-balances') " +
                    "ORDER BY entity_key");

            System.out.println("=====char-length totals for meaning text across ALL entities (for scale estimate)=====");
            query(conn, "SELECT count(*) AS entity_count, " +
                    "sum(coalesce(length(operational_meaning),0)) AS total_meaning_chars, " +
                    "sum(coalesce(length(investigation_hints),0)) AS total_hints_chars, " +
                    "avg(coalesce(length(operational_meaning),0) + coalesce(length(investigation_hints),0)) AS avg_chars_per_entity " +
                    "FROM nexus_business_entity");

            System.out.println("=====relationship row avg size estimate=====");
            query(conn, "SELECT count(*) AS rel_count, avg(coalesce(length(join_guidance),0)) AS avg_join_guidance_chars FROM nexus_entity_relationship");
        } catch (Exception e) {
            System.out.println("TOP-LEVEL ERROR: " + e);
        }
    }

    private void query(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            int count = 0;
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) sb.append(" | ");
                    sb.append(rs.getString(i));
                }
                System.out.println(sb);
                count++;
            }
            if (count == 0) System.out.println("(no rows)");
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }
}
