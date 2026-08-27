package com.sei.nexus.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.agent.AgentRepository;
import com.sei.nexus.agent.AgentService;
import com.sei.nexus.agent.NexusAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRO-21 — wizard apply = canonical registration pipeline + wizard-only
 * bootstrap tail. Verifies the wizard delegates registration unchanged, that
 * bootstrap (suggested questions, default agent) still runs on the wizard path,
 * that the guarded agent creation stays first-run-only, and that the response
 * contract the wizard UI reads is preserved.
 */
class OnboardingApplyBootstrapTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    static class FakeRegistration extends MetadataRegistrationService {
        final List<Map<String, Object>> requests = new ArrayList<>();
        FakeRegistration() { super(null, null, null, null); }
        @Override
        public RegistrationResult register(Map<String, Object> request, String userEmail) {
            requests.add(request);
            return new RegistrationResult(2, 2, 3, 4, List.of());
        }
    }

    static class FakeSettings extends TenantSettingsRepository {
        final Map<String, String> stored = new LinkedHashMap<>();
        FakeSettings() { super(null); }
        @Override public void set(String key, String value) { stored.put(key, value); }
        @Override public boolean isTrue(String key) { return false; }
    }

    static class FakeAgentRepository extends AgentRepository {
        List<NexusAgent> active = List.of();
        FakeAgentRepository() { super(null); }
        @Override public List<NexusAgent> findActive() { return active; }
    }

    static class FakeAgentService extends AgentService {
        final List<Map<String, Object>> created = new ArrayList<>();
        FakeAgentService() { super(null, null, null); }
        @Override public NexusAgent createOrUpdate(Map<String, Object> req, String userEmail) {
            created.add(req);
            return null;
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private FakeRegistration registration;
    private FakeSettings settings;
    private FakeAgentRepository agentRepository;
    private FakeAgentService agentService;
    private OnboardingService service;

    @BeforeEach
    void setUp() {
        registration    = new FakeRegistration();
        settings        = new FakeSettings();
        agentRepository = new FakeAgentRepository();
        agentService    = new FakeAgentService();
        service = new OnboardingService(settings, null, null, null, null, null,
                new ObjectMapper(), null, agentService, agentRepository,
                registration, null, null, null,
                new com.sei.nexus.reasoning.ReasoningEventBus(new ObjectMapper()),
                Runnable::run, null);
    }

    private static Map<String, Object> applyRequest() {
        Map<String, Object> approvedEntity = new LinkedHashMap<>();
        approvedEntity.put("approved", true);
        approvedEntity.put("tableName", "stores");
        approvedEntity.put("suggestedQuestions",
                List.of("How many stores are open?", "Which stores missed target?"));

        Map<String, Object> rejectedEntity = new LinkedHashMap<>();
        rejectedEntity.put("approved", false);
        rejectedEntity.put("tableName", "fiscal_periods");
        rejectedEntity.put("suggestedQuestions", List.of("Question from a rejected draft"));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("connectionKey", "conn-1");
        request.put("schemaName", "retail_core");
        request.put("domainKey", "PLATFORM");
        request.put("entities", List.of(approvedEntity, rejectedEntity));
        return request;
    }

    // ── delegation + bootstrap ───────────────────────────────────────────────

    @Test
    void applyDelegatesRegistrationUnchangedThenRunsBootstrap() {
        Map<String, Object> request = applyRequest();
        Map<String, Object> result = service.applySelections(request, "user@x.com");

        // Registration receives the exact request object, untransformed — the same
        // input contract any other entry point (e.g. /semantic/discover/apply) sends.
        assertEquals(1, registration.requests.size());
        assertSame(request, registration.requests.get(0));

        // Bootstrap: suggested questions from APPROVED drafts only
        String storedQuestions = settings.stored.get("onboarding_suggested_questions");
        assertNotNull(storedQuestions);
        assertTrue(storedQuestions.contains("How many stores are open?"));
        assertFalse(storedQuestions.contains("rejected draft"),
                "questions from unapproved drafts must not be persisted");

        // Bootstrap: default agent created when no active agents exist
        assertEquals(1, agentService.created.size());
        Map<String, Object> agent = agentService.created.get(0);
        assertEquals("data-analyst", agent.get("agentKey"));
        assertEquals("PLATFORM",     agent.get("domainKeys"));
        assertEquals("conn-1",       agent.get("connectionKeys"));

        // Response contract the wizard UI reads — unchanged keys, registration counts
        assertEquals(2, result.get("entities_created"));
        assertEquals(3, result.get("vocab_terms_created"));
        assertEquals(2, result.get("data_objects_created"));
        assertEquals(List.of("How many stores are open?", "Which stores missed target?"),
                result.get("suggested_questions"));
        assertEquals(List.of(), result.get("failures"));
    }

    @Test
    void defaultAgentSkippedWhenActiveAgentsExist() {
        agentRepository.active = Collections.singletonList(null);   // any non-empty list

        service.applySelections(applyRequest(), "user@x.com");

        assertTrue(agentService.created.isEmpty(),
                "bootstrap agent creation is first-run-only");
    }
}
