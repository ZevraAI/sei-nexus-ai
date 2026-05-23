package com.sei.nexus.pack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Matches pack entity definitions to actual table names discovered in a tenant's database.
 *
 * <h3>Matching strategy (two phases)</h3>
 * <ol>
 *   <li><b>Pattern matching</b> — for each pack entity, check whether any discovered
 *       table name contains one of the entity's {@code table_patterns} as a substring
 *       (case-insensitive).  If exactly one table matches, use it.  This handles the
 *       common case without any LLM call.</li>
 *   <li><b>LLM fallback</b> — for entities where pattern matching found no match or
 *       multiple ambiguous candidates, ask the LLM to make the final decision.
 *       The prompt is generic — it does not assume any specific industry.</li>
 * </ol>
 *
 * <p>Entities that cannot be matched with confidence ≥ 0.6 are returned in the
 * {@code unmatched} list and shown in the preview as requiring manual mapping.
 */
@Component
public class PackEntityMapper {

    private static final Logger log = LoggerFactory.getLogger(PackEntityMapper.class);
    private static final double MIN_CONFIDENCE = 0.6;

    private static final String SYSTEM_PROMPT = """
            You are matching business entity names from an industry pack to actual database table names.
            For each pack entity, identify the best matching table from the available list.
            Consider partial matches, common abbreviations, and semantic similarity.
            If no table is a reasonable match, omit that entity from the response.

            Return a JSON object only:
            {"EntityName": "actual_table_name", "OtherEntity": "other_table", ...}

            Only include entities where you are confident (>= 60%) of the match.
            """;

    private final AzureOpenAiClient aiClient;
    private final ObjectMapper      objectMapper;

    public PackEntityMapper(AzureOpenAiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient     = aiClient;
        this.objectMapper = objectMapper;
    }

    public record EntityMatchResult(
            Map<String, String> matched,     // entity name → table name
            List<String>        unmatched    // entity names with no confident match
    ) {}

    /**
     * Match pack entities to discovered table names.
     *
     * @param pack           The industry pack to match.
     * @param discoveredTables Table names from the tenant's connected databases.
     * @return Matched and unmatched entity lists.
     */
    public EntityMatchResult match(IndustryPack pack, List<String> discoveredTables) {
        if (pack.entities() == null || pack.entities().isEmpty() || discoveredTables == null || discoveredTables.isEmpty()) {
            return new EntityMatchResult(Map.of(), pack.entities() == null ? List.of()
                    : pack.entities().stream().map(PackEntity::name).toList());
        }

        Map<String, String> matched   = new LinkedHashMap<>();
        List<PackEntity>    needsLlm  = new ArrayList<>();

        // Phase 1: fast pattern matching
        for (PackEntity entity : pack.entities()) {
            String table = patternMatch(entity, discoveredTables);
            if (table != null) {
                matched.put(entity.name(), table);
                log.debug("Pack entity '{}' pattern-matched to '{}'", entity.name(), table);
            } else {
                needsLlm.add(entity);
            }
        }

        // Phase 2: LLM fallback for unresolved entities
        if (!needsLlm.isEmpty()) {
            try {
                Map<String, String> llmMatches = llmMatch(needsLlm, discoveredTables);
                matched.putAll(llmMatches);
            } catch (Exception e) {
                log.warn("LLM entity matching failed: {}", e.getMessage());
            }
        }

        // Build unmatched list
        List<String> unmatched = pack.entities().stream()
                .map(PackEntity::name)
                .filter(name -> !matched.containsKey(name))
                .toList();

        log.info("Pack '{}': matched {}/{} entities", pack.packId(), matched.size(), pack.entities().size());
        return new EntityMatchResult(matched, unmatched);
    }

    // ── Pattern matching ──────────────────────────────────────────────────────

    /**
     * Returns the best pattern-matched table name, or null if no confident match.
     *
     * <p>Exact-contains match on table_patterns wins. If multiple tables match,
     * the one whose name most closely resembles the pattern (shorter name = less noisy)
     * is preferred. If still ambiguous, escalates to the LLM.
     */
    private String patternMatch(PackEntity entity, List<String> tables) {
        List<String> patterns = entity.tablePatterns();
        if (patterns == null || patterns.isEmpty()) return null;

        List<String> candidates = new ArrayList<>();
        for (String pattern : patterns) {
            String lowerPattern = pattern.toLowerCase();
            for (String table : tables) {
                String lowerTable = table.toLowerCase()
                        .replaceAll("[^a-z0-9]", ""); // normalise
                String lowerPatternNorm = lowerPattern.replaceAll("[^a-z0-9]", "");
                if (lowerTable.contains(lowerPatternNorm) || lowerPatternNorm.contains(lowerTable)) {
                    if (!candidates.contains(table)) candidates.add(table);
                }
            }
        }

        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        // Multiple candidates — prefer the one that is the shortest (least noisy name)
        candidates.sort(Comparator.comparingInt(String::length));
        return candidates.get(0);
    }

    // ── LLM fallback ──────────────────────────────────────────────────────────

    private Map<String, String> llmMatch(List<PackEntity> entities, List<String> tables) throws Exception {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Available tables: ").append(String.join(", ", tables)).append("\n\n");
        prompt.append("Entities to match:\n");
        for (PackEntity e : entities) {
            prompt.append("- ").append(e.name())
                  .append(": ").append(e.description())
                  .append(" (aliases: ").append(String.join(", ", safeList(e.aliases()))).append(")\n");
        }

        String raw = aiClient.chat(List.of(ChatMessage.user(prompt.toString())), SYSTEM_PROMPT);
        String json = extractJson(raw);
        Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});

        // Filter out matches where the table doesn't exist in discovered tables
        Set<String> tableSet = new HashSet<>(tables);
        Map<String, String> verified = new LinkedHashMap<>();
        parsed.forEach((entity, table) -> {
            if (tableSet.contains(table)) {
                verified.put(entity, table);
                log.debug("Pack entity '{}' LLM-matched to '{}'", entity, table);
            }
        });
        return verified;
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    private List<String> safeList(List<String> list) {
        return list != null ? list : List.of();
    }
}
