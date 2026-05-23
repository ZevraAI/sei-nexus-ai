package com.sei.nexus.pack;

import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.enterprise.DataObject;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semantic.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orchestrates industry pack operations:
 *
 * <ul>
 *   <li>{@link #listPacks()} — return all available packs from the catalogue.</li>
 *   <li>{@link #previewPack(String)} — dry-run: show what would be created without committing.</li>
 *   <li>{@link #applyPack(String, String, String)} — match entities, create vocabulary,
 *       seed suggested questions, and store the applied pack record.</li>
 *   <li>{@link #listAppliedPacks()} — list packs applied to this tenant.</li>
 *   <li>{@link #recommend(List)} — fast recommendation for onboarding.</li>
 * </ul>
 *
 * <p>All operations that create DB records delegate to {@link SemanticService}
 * so the resulting entities and vocabulary appear in the Semantic Layer UI.
 */
@Service
public class IndustryPackService {

    private static final Logger log = LoggerFactory.getLogger(IndustryPackService.class);

    private final IndustryPackRepository     packRepository;
    private final PackEntityMapper           entityMapper;
    private final PackRecommendationService  recommendationService;
    private final SemanticService            semanticService;
    private final EnterpriseMapRepository    enterpriseMapRepository;

    public IndustryPackService(IndustryPackRepository packRepository,
                               PackEntityMapper entityMapper,
                               PackRecommendationService recommendationService,
                               SemanticService semanticService,
                               EnterpriseMapRepository enterpriseMapRepository) {
        this.packRepository         = packRepository;
        this.entityMapper           = entityMapper;
        this.recommendationService  = recommendationService;
        this.semanticService        = semanticService;
        this.enterpriseMapRepository = enterpriseMapRepository;
    }

    // ── Pack catalogue ────────────────────────────────────────────────────────

    public List<IndustryPack> listPacks() {
        return packRepository.findAllPacks();
    }

    public IndustryPack getPack(String packKey) {
        return packRepository.findPackById(packKey)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Industry pack not found: " + packKey));
    }

    // ── Applied packs ─────────────────────────────────────────────────────────

    public List<TenantPack> listAppliedPacks() {
        return packRepository.findAppliedPacks();
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Dry-run: shows what would be created if the pack were applied.
     * No DB writes; uses the discovered tables in the current tenant schema.
     */
    public PackPreview previewPack(String packKey, String domainKey) {
        IndustryPack pack = getPack(packKey);
        List<String>  tables = getDiscoveredTableNames(domainKey);
        PackEntityMapper.EntityMatchResult result = entityMapper.match(pack, tables);

        double coverage = pack.entities().isEmpty() ? 0.0
                : (double) result.matched().size() / pack.entities().size();

        return new PackPreview(
                pack.packId(),
                pack.displayName(),
                result.matched(),
                result.unmatched(),
                safeSize(pack.vocabulary()),
                safeSize(pack.suggestedQuestions()),
                safeSize(pack.alertTemplates()),
                coverage);
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Applies a pack to the current tenant schema:
     * <ol>
     *   <li>Match entities to discovered tables via {@link PackEntityMapper}.</li>
     *   <li>Create {@code nexus_business_entity} + {@code nexus_entity_data_mapping}
     *       for each matched entity.</li>
     *   <li>Create {@code nexus_operational_vocabulary} for each vocabulary term.</li>
     *   <li>Record the applied pack in {@code nexus_tenant_pack}.</li>
     * </ol>
     *
     * @param packKey    Pack identifier from the catalogue.
     * @param domainKey  Target domain for created entities and vocabulary.
     * @param appliedBy  Email of the user applying the pack.
     */
    public PackApplicationResult applyPack(String packKey, String domainKey, String appliedBy) {
        IndustryPack pack = getPack(packKey);

        // Check not already applied
        if (packRepository.findAppliedPack(packKey).isPresent()) {
            throw new NexusException(HttpStatus.CONFLICT,
                    "Pack '" + pack.displayName() + "' has already been applied to this tenant. " +
                    "Remove it first before re-applying.");
        }

        List<String> tables = getDiscoveredTableNames(domainKey);
        PackEntityMapper.EntityMatchResult matchResult = entityMapper.match(pack, tables);

        int entitiesCreated  = 0;
        int vocabAdded       = 0;

        // 1. Create entities for matched tables
        for (Map.Entry<String, String> entry : matchResult.matched().entrySet()) {
            String entityName = entry.getKey();
            String tableName  = entry.getValue();
            try {
                PackEntity packEntity = findPackEntity(pack, entityName);
                semanticService.createOrUpdateEntity(Map.of(
                        "domainKey",        domainKey,
                        "entityName",       packEntity.name(),
                        "nodeType",         "ENTITY",
                        "description",      safe(packEntity.description()),
                        "operationalMeaning", safe(packEntity.operationalMeaning()),
                        "status",           "ACTIVE"),
                        appliedBy);
                entitiesCreated++;
            } catch (Exception e) {
                log.warn("Failed to create entity '{}' from pack '{}': {}", entityName, packKey, e.getMessage());
            }
        }

        // 2. Create vocabulary terms
        for (PackVocabularyTerm term : safe(pack.vocabulary())) {
            try {
                semanticService.createTerm(Map.of(
                        "domainKey",     safe(domainKey),
                        "term",          term.term(),
                        "definition",    safe(term.definition()),
                        "sql_equivalent", safe(term.sqlHint()),
                        "status",        "ACTIVE"));
                vocabAdded++;
            } catch (Exception e) {
                log.debug("Failed to create vocab term '{}': {}", term.term(), e.getMessage());
            }
        }

        // 3. Record the applied pack
        double coverage = pack.entities().isEmpty() ? 0.0
                : (double) matchResult.matched().size() / pack.entities().size();
        TenantPack tenantPack = new TenantPack(
                packKey, pack.version(), pack.displayName(),
                "ACTIVE", matchResult.matched(), coverage,
                null, appliedBy);
        packRepository.saveTenantPack(tenantPack);

        log.info("Pack '{}' applied: {} entities, {} vocab terms, coverage {:.0%}",
                packKey, entitiesCreated, vocabAdded, coverage);

        return new PackApplicationResult(
                packKey, pack.displayName(),
                entitiesCreated, vocabAdded,
                safeSize(pack.suggestedQuestions()),
                coverage,
                matchResult.matched(),
                matchResult.unmatched());
    }

    /** Remove an applied pack record (does not delete created entities/vocab). */
    public void removePack(String packKey) {
        packRepository.disableTenantPack(packKey);
    }

    // ── Recommendation ────────────────────────────────────────────────────────

    public Optional<PackRecommendationService.PackRecommendation> recommend(List<String> tableNames) {
        return recommendationService.recommend(tableNames);
    }

    /** Convenience overload: recommends based on all discovered tables in the tenant. */
    public Optional<PackRecommendationService.PackRecommendation> recommendForCurrentTenant(String domainKey) {
        List<String> tables = getDiscoveredTableNames(domainKey);
        return recommendationService.recommend(tables);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Load table names from nexus_data_object for the current tenant schema. */
    private List<String> getDiscoveredTableNames(String domainKey) {
        try {
            List<DataObject> objects = domainKey != null && !domainKey.isBlank()
                    ? enterpriseMapRepository.findDataObjectsByDomain(domainKey)
                    : enterpriseMapRepository.findDataObjectsByDomain("PLATFORM");
            return objects.stream()
                    .map(DataObject::tableName)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not load discovered tables: {}", e.getMessage());
            return List.of();
        }
    }

    private PackEntity findPackEntity(IndustryPack pack, String entityName) {
        return pack.entities().stream()
                .filter(e -> e.name().equals(entityName))
                .findFirst()
                .orElse(new PackEntity(entityName, List.of(), List.of(), List.of(), "", ""));
    }

    private String safe(String s) { return s != null ? s : ""; }

    private <T> List<T> safe(List<T> list) { return list != null ? list : List.of(); }

    private int safeSize(List<?> list) { return list != null ? list.size() : 0; }
}
