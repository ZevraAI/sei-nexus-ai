package com.sei.nexus.pack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Recommends the best-fitting industry pack based on the table names discovered
 * in a tenant's connected database.
 *
 * <p>Uses pure string pattern matching (no LLM) so it runs in milliseconds
 * during onboarding.  The LLM-based exact matching in {@link PackEntityMapper}
 * is only invoked when the pack is previewed or applied.
 *
 * <p>A pack is recommended only when its pattern-match coverage is ≥ 40%.
 * Below that threshold, the schema is too ambiguous to make a useful suggestion.
 */
@Component
public class PackRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(PackRecommendationService.class);
    private static final double MIN_COVERAGE = 0.40;

    public record PackRecommendation(
            String       packKey,
            String       displayName,
            double       coverageScore,  // fraction of pack entities with a pattern match
            List<String> matchedTables   // tables that contributed to the match
    ) {}

    private final IndustryPackRepository packRepository;

    public PackRecommendationService(IndustryPackRepository packRepository) {
        this.packRepository = packRepository;
    }

    /**
     * Recommends the best-fitting pack for the given discovered table names.
     *
     * @param discoveredTables Table names from the tenant's connected databases.
     * @return The best recommendation, or empty if no pack meets the threshold.
     */
    public Optional<PackRecommendation> recommend(List<String> discoveredTables) {
        if (discoveredTables == null || discoveredTables.isEmpty()) return Optional.empty();

        List<IndustryPack> packs = packRepository.findAllPacks();
        PackRecommendation best  = null;

        for (IndustryPack pack : packs) {
            PackRecommendation rec = score(pack, discoveredTables);
            if (rec.coverageScore() >= MIN_COVERAGE) {
                if (best == null || rec.coverageScore() > best.coverageScore()) {
                    best = rec;
                }
            }
        }

        if (best != null) {
            log.info("Pack recommendation: '{}' (coverage {:.0%})", best.packKey(), best.coverageScore());
        }
        return Optional.ofNullable(best);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PackRecommendation score(IndustryPack pack, List<String> tables) {
        if (pack.entities() == null || pack.entities().isEmpty()) {
            return new PackRecommendation(pack.packId(), pack.displayName(), 0.0, List.of());
        }

        int matched = 0;
        List<String> matchedTables = new ArrayList<>();

        for (PackEntity entity : pack.entities()) {
            String table = patternMatch(entity, tables);
            if (table != null) {
                matched++;
                if (!matchedTables.contains(table)) matchedTables.add(table);
            }
        }

        double coverage = (double) matched / pack.entities().size();
        return new PackRecommendation(pack.packId(), pack.displayName(), coverage, matchedTables);
    }

    private String patternMatch(PackEntity entity, List<String> tables) {
        List<String> patterns = entity.tablePatterns();
        if (patterns == null) return null;
        for (String pattern : patterns) {
            String lp = pattern.toLowerCase().replaceAll("[^a-z0-9]", "");
            for (String table : tables) {
                String lt = table.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (lt.contains(lp) || lp.contains(lt)) return table;
            }
        }
        return null;
    }
}
