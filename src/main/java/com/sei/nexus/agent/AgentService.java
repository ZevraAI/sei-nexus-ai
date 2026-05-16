package com.sei.nexus.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.Keys;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.sql.SqlSafetyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;
    private final SqlSafetyService sqlSafetyService;
    private final ObjectMapper objectMapper;

    public AgentService(AgentRepository agentRepository,
                         SqlSafetyService sqlSafetyService,
                         ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.sqlSafetyService = sqlSafetyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates or updates a NexusAgent. Saves a version snapshot on each change.
     */
    public NexusAgent createOrUpdate(Map<String, Object> req, String userEmail) {
        String name = required(req, "name");

        String agentKey = req.containsKey("agentKey") && req.get("agentKey") != null
            ? req.get("agentKey").toString()
            : Keys.key(name);

        boolean exists = agentRepository.findByKey(agentKey).isPresent();
        int versionNo = exists
            ? agentRepository.findByKey(agentKey).map(NexusAgent::versionNo).orElse(0) + 1
            : 1;

        Instant now = Instant.now();
        boolean restApiEnabled = Boolean.TRUE.equals(req.get("restApiEnabled"));

        NexusAgent agent = new NexusAgent(
            agentKey,
            name,
            (String) req.get("purpose"),
            (String) req.get("domainKeys"),
            (String) req.get("connectionKeys"),
            restApiEnabled,
            (String) req.getOrDefault("actionScope", "READ_ONLY"),
            versionNo,
            "ACTIVE",
            exists ? agentRepository.findByKey(agentKey).map(NexusAgent::createdBy).orElse(userEmail) : userEmail,
            exists ? agentRepository.findByKey(agentKey).map(NexusAgent::createdAt).orElse(now) : now,
            now);

        agentRepository.save(agent);

        // Save version snapshot
        try {
            String snapshot = objectMapper.writeValueAsString(agent);
            String versionKey = "agver-" + Keys.uniqueKey("v" + versionNo);
            AgentVersion version = new AgentVersion(versionKey, agentKey, versionNo, snapshot, now);
            agentRepository.saveVersion(version);
        } catch (Exception e) {
            log.warn("Failed to create version snapshot for agent {}: {}", agentKey, e.getMessage());
        }

        return agentRepository.findByKey(agentKey).orElse(agent);
    }

    /**
     * Rolls back an agent to a specific version by deserializing the snapshot and saving as the new version.
     */
    public NexusAgent rollback(String agentKey, int versionNo) {
        NexusAgent current = agentRepository.findByKey(agentKey)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Agent not found: " + agentKey));

        AgentVersion version = agentRepository.findVersionByNo(agentKey, versionNo)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Version " + versionNo + " not found for agent: " + agentKey));

        try {
            Map<String, Object> snapshotMap = objectMapper.readValue(version.snapshot(),
                new TypeReference<Map<String, Object>>() {});

            int newVersionNo = current.versionNo() + 1;
            Instant now = Instant.now();

            NexusAgent restored = new NexusAgent(
                agentKey,
                (String) snapshotMap.getOrDefault("name", current.name()),
                (String) snapshotMap.get("purpose"),
                (String) snapshotMap.get("domainKeys"),
                (String) snapshotMap.get("connectionKeys"),
                Boolean.TRUE.equals(snapshotMap.get("restApiEnabled")),
                (String) snapshotMap.getOrDefault("actionScope", "READ_ONLY"),
                newVersionNo,
                "ACTIVE",
                current.createdBy(),
                current.createdAt(),
                now);

            agentRepository.save(restored);

            // Save new version snapshot capturing the rollback
            String newSnapshot = objectMapper.writeValueAsString(restored);
            String newVersionKey = "agver-" + Keys.uniqueKey("v" + newVersionNo);
            AgentVersion newVersion = new AgentVersion(newVersionKey, agentKey, newVersionNo, newSnapshot, now);
            agentRepository.saveVersion(newVersion);

            return agentRepository.findByKey(agentKey).orElse(restored);

        } catch (NexusException e) {
            throw e;
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Rollback failed: " + e.getMessage());
        }
    }

    /**
     * Adds a playbook to the given agent.
     */
    public AgentPlaybook addPlaybook(String agentKey, Map<String, Object> req) {
        agentRepository.findByKey(agentKey)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Agent not found: " + agentKey));

        String name = required(req, "name");
        String playbookKey = req.containsKey("playbookKey") && req.get("playbookKey") != null
            ? req.get("playbookKey").toString()
            : Keys.uniqueKey("pb-" + agentKey + "-" + Keys.key(name));

        Double confidenceThreshold = req.get("confidenceThreshold") != null
            ? ((Number) req.get("confidenceThreshold")).doubleValue() : null;
        Integer maxSteps = req.get("maxInvestigationSteps") != null
            ? ((Number) req.get("maxInvestigationSteps")).intValue() : null;

        Instant now = Instant.now();
        AgentPlaybook playbook = new AgentPlaybook(
            playbookKey,
            agentKey,
            name,
            (String) req.get("triggerConditions"),
            (String) req.get("investigationSteps"),
            (String) req.get("escalationRules"),
            confidenceThreshold,
            (String) req.get("preferredEvidenceOrder"),
            maxSteps,
            "ACTIVE",
            now,
            now);

        agentRepository.savePlaybook(playbook);
        return playbook;
    }

    /**
     * Adds a KPI to the given agent. Validates measurementSql if provided.
     */
    public AgentKpi addKpi(String agentKey, Map<String, Object> req) {
        agentRepository.findByKey(agentKey)
            .orElseThrow(() -> new NexusException(HttpStatus.NOT_FOUND,
                "Agent not found: " + agentKey));

        String kpiName = required(req, "kpiName");

        // Validate measurementSql if provided
        String measurementSql = (String) req.get("measurementSql");
        if (measurementSql != null && !measurementSql.isBlank()) {
            var safety = sqlSafetyService.validate(measurementSql);
            if (!safety.safe()) {
                throw new NexusException(HttpStatus.BAD_REQUEST,
                    "measurementSql failed safety check: " + safety.reason());
            }
        }

        String kpiKey = req.containsKey("kpiKey") && req.get("kpiKey") != null
            ? req.get("kpiKey").toString()
            : Keys.uniqueKey("kpi-" + agentKey + "-" + Keys.key(kpiName));

        Double thresholdWarning = req.get("thresholdWarning") != null
            ? ((Number) req.get("thresholdWarning")).doubleValue() : null;
        Double thresholdCritical = req.get("thresholdCritical") != null
            ? ((Number) req.get("thresholdCritical")).doubleValue() : null;
        Integer refreshInterval = req.get("refreshIntervalHrs") != null
            ? ((Number) req.get("refreshIntervalHrs")).intValue() : null;

        AgentKpi kpi = new AgentKpi(
            kpiKey,
            agentKey,
            (String) req.get("domainKey"),
            kpiName,
            (String) req.get("kpiDescription"),
            (String) req.get("measurementObjectKey"),
            measurementSql,
            thresholdWarning,
            thresholdCritical,
            Boolean.TRUE.equals(req.get("higherIsBetter")),
            refreshInterval,
            "ACTIVE",
            Instant.now());

        agentRepository.saveKpi(kpi);
        return kpi;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String required(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new NexusException(HttpStatus.BAD_REQUEST, key + " is required");
        }
        return val.toString();
    }
}
