package com.sei.nexus.integration;

import com.sei.nexus.agentrunner.ZevraAgent;
import com.sei.nexus.agentrunner.ZevraAgentRepository;
import com.sei.nexus.auth.UserAccount;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.pack.IndustryPack;
import com.sei.nexus.pack.IndustryPackRepository;
import com.sei.nexus.pack.IndustryPackService;
import com.sei.nexus.pack.PackAgentDefinition;
import com.sei.nexus.sql.DynamicSqlService;
import com.sei.nexus.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages integration templates — system-specific intelligence packs (ServiceNow, Salesforce, etc.)
 * that go beyond generic industry packs by also creating pre-built Zevra Agents and validating
 * table coverage against an actual connected database.
 *
 * <p>Extends {@link IndustryPackService} behaviour: delegates entity/vocab creation there,
 * then adds the agent creation step.
 */
@Service
public class IntegrationTemplateService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTemplateService.class);

    private final IndustryPackRepository  packRepository;
    private final IndustryPackService     packService;
    private final ZevraAgentRepository    agentRepository;
    private final DynamicSqlService       dynamicSql;

    public IntegrationTemplateService(IndustryPackRepository packRepository,
                                       IndustryPackService packService,
                                       ZevraAgentRepository agentRepository,
                                       DynamicSqlService dynamicSql) {
        this.packRepository  = packRepository;
        this.packService     = packService;
        this.agentRepository = agentRepository;
        this.dynamicSql      = dynamicSql;
    }

    // ── Template catalogue ────────────────────────────────────────────────────

    /** Returns only packs marked as INTEGRATION category. */
    public List<IndustryPack> listTemplates() {
        return packRepository.findAllPacks().stream()
                .filter(IndustryPack::isIntegration)
                .collect(Collectors.toList());
    }

    public IndustryPack getTemplate(String templateId) {
        IndustryPack pack = packRepository.findPackById(templateId)
                .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                        "Integration template not found: " + templateId));
        if (!pack.isIntegration()) {
            throw new NexusException(HttpStatus.NOT_FOUND,
                    templateId + " is an industry pack, not an integration template");
        }
        return pack;
    }

    // ── Connection validation ─────────────────────────────────────────────────

    /**
     * Checks which of the template's expected tables exist in the given connection.
     * Returns a coverage report so the user can remap missing tables before applying.
     * Works for any connection type — direct JDBC or data warehouse mirror.
     */
    public TemplateCoverage validateConnection(String templateId, String connectionKey) {
        IndustryPack template = getTemplate(templateId);

        List<String> actualTables;
        try {
            actualTables = dynamicSql.listTablesWithColumnCounts(connectionKey, "public")
                    .stream()
                    .map(r -> String.valueOf(r.get("table_name")).toLowerCase())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "Could not connect to '" + connectionKey + "': " + e.getMessage());
        }

        List<EntityCoverage> coverage = new ArrayList<>();
        for (var entity : template.entities()) {
            // Find the best matching actual table using the entity's pattern list
            String matched = entity.tablePatterns().stream()
                    .filter(p -> actualTables.stream().anyMatch(t -> t.equals(p) || t.contains(p)))
                    .map(p -> actualTables.stream()
                            .filter(t -> t.equals(p) || t.contains(p))
                            .findFirst().orElse(null))
                    .filter(t -> t != null)
                    .findFirst()
                    .orElse(null);
            coverage.add(new EntityCoverage(entity.name(), matched, matched != null));
        }

        long matched = coverage.stream().filter(EntityCoverage::found).count();
        double pct   = template.entities().isEmpty() ? 0.0
                : (double) matched / template.entities().size() * 100;

        return new TemplateCoverage(templateId, connectionKey, coverage, (int) matched,
                template.entities().size(), pct);
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Applies the integration template to the current tenant:
     * <ol>
     *   <li>Uses the existing {@link IndustryPackService#applyPack} to create domain,
     *       entities, vocabulary, and record the pack.</li>
     *   <li>Creates the pre-built Zevra Agent defined in the template JSON.</li>
     * </ol>
     *
     * @param templateId    Template identifier (e.g. "servicenow-itsm-v1")
     * @param domainKey     Domain to attach entities to (created if absent)
     * @param connectionKey The connection the agent will query
     * @param appliedBy     Email of the user applying
     */
    public TemplateApplicationResult applyTemplate(String templateId, String domainKey,
                                                    String connectionKey, String appliedBy) {
        IndustryPack template = getTemplate(templateId);

        // Delegate core entity + vocab creation to existing pack infrastructure
        var packResult = packService.applyPack(templateId, domainKey, connectionKey, appliedBy);

        // Create the pre-built agent if defined
        String agentId = null;
        if (template.agentDefinition() != null) {
            agentId = createTemplateAgent(template.agentDefinition(), connectionKey,
                    TenantContext.getSchema(), appliedBy);
        }

        log.info("Integration template '{}' applied: {} entities, {} vocab, agent={}",
                templateId, packResult.entitiesCreated(), packResult.vocabularyTermsAdded(), agentId);

        return new TemplateApplicationResult(
                templateId,
                template.displayName(),
                packResult.entitiesCreated(),
                packResult.vocabularyTermsAdded(),
                agentId,
                domainKey,
                connectionKey);
    }

    /** Returns applied integration templates for this tenant. */
    public List<Map<String, Object>> listApplied() {
        return packRepository.findAppliedPacks().stream()
                .filter(tp -> {
                    try {
                        return packRepository.findPackById(tp.packKey())
                                .map(IndustryPack::isIntegration).orElse(false);
                    } catch (Exception e) { return false; }
                })
                .map(tp -> Map.<String, Object>of(
                        "pack_key",    tp.packKey(),
                        "display_name", tp.displayName(),
                        "applied_at",   tp.appliedAt() != null ? tp.appliedAt().toString() : "",
                        "applied_by",   tp.appliedBy() != null ? tp.appliedBy() : ""))
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String createTemplateAgent(PackAgentDefinition def, String connectionKey,
                                        String tenantSchema, String createdBy) {
        String agentId   = Keys.uniqueKey("ag");
        String slug      = def.slug() != null ? def.slug() : slugify(def.name());
        int    maxIter   = def.maxIterations() > 0 ? def.maxIterations() : 8;

        // If an agent with this slug already exists, skip creation
        try {
            if (agentRepository.slugExists(slug, tenantSchema)) {
                log.debug("Template agent '{}' already exists, skipping", slug);
                return null;
            }
        } catch (Exception ignored) {}

        ZevraAgent agent = new ZevraAgent(
                agentId, tenantSchema, def.name(), slug,
                def.description() != null ? def.description() : "",
                def.persona()     != null ? def.persona()      : "",
                def.goal()        != null ? def.goal()         : "",
                connectionKey != null ? List.of(connectionKey) : List.of(),
                maxIter, "ACTIVE", createdBy, null, null);

        try {
            agentRepository.insertAgent(agent);
            return agentId;
        } catch (Exception e) {
            log.warn("Could not create template agent '{}': {}", def.name(), e.getMessage());
            return null;
        }
    }

    private String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record EntityCoverage(String entityName, String matchedTable, boolean found) {}

    public record TemplateCoverage(
            String             templateId,
            String             connectionKey,
            List<EntityCoverage> entities,
            int                matchedCount,
            int                totalCount,
            double             coveragePct
    ) {}

    public record TemplateApplicationResult(
            String templateId,
            String displayName,
            int    entitiesCreated,
            int    vocabAdded,
            String agentId,
            String domainKey,
            String connectionKey
    ) {}
}
