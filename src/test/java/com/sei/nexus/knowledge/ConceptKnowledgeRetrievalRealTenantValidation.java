package com.sei.nexus.knowledge;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.tenant.Tenant;
import com.sei.nexus.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * DIAGNOSTIC ONLY (real OpenAI, real tenant Vector Store — never runs in the normal suite, per
 * the {@code *RealTenantValidation} naming convention already established this session; it does
 * NOT match Surefire's default {@code **&#47;*Test.java} inclusion pattern, so {@code mvn test}
 * never picks it up). Run individually from an IDE, or via
 * {@code mvn test -Dtest=ConceptKnowledgeRetrievalRealTenantValidation -DfailIfNoTests=false}
 * against an environment with a real {@code OPENAI_API_KEY} configured.
 *
 * <p>Phase 2A §12/§13/§15 manual validation procedure:
 * <ol>
 *   <li>Pick one disposable/test tenant slug that already has a Phase 1 {@code
 *       ai_knowledge_vector_store_id} (provision one via {@code TenantProvisioningService} first
 *       if needed — never run this against a production tenant).</li>
 *   <li>Run {@link ConceptKnowledgeMaterializationService#materializeTenantConcepts} for that
 *       tenant (e.g. via a throwaway admin endpoint, or a debugger-invoked call) so its Vector
 *       Store actually has concept knowledge in it.</li>
 *   <li>Set {@code TEST_TENANT_SLUG} below to that tenant's slug and run this class.</li>
 *   <li>Read the printed {@link AzureOpenAiClient#fileSearchQuery} responses for each question and
 *       manually confirm: the Vector Store exists and is queryable, the expected concept's
 *       knowledge is present in the retrieved content, and — where a genuinely unrelated concept
 *       is also materialized for the same tenant — that it is not incorrectly surfaced for an
 *       unrelated question.</li>
 * </ol>
 *
 * <p><strong>What this does NOT evaluate</strong> (per the task's explicit instruction): whether
 * an LLM would have chosen the "correct" concept for a given question. That is a semantic-decision
 * question for a later phase. This class only proves materialization → indexing → retrieval works
 * against the real API — i.e. that concept knowledge uploaded by Phase 2A is actually findable.
 */
@SpringBootTest
class ConceptKnowledgeRetrievalRealTenantValidation {

    private static final String TEST_TENANT_SLUG = "CHANGE_ME_TO_A_REAL_TEST_TENANT_SLUG";

    @Autowired private TenantRepository tenantRepository;
    @Autowired private AzureOpenAiClient aiClient;

    private static final String[] VALIDATION_QUESTIONS = {
            "purchase order",
            "sales transaction",
            "customer order",
            "show me open orders",
    };

    @Test
    void manuallyValidateRetrievalForConfiguredTestTenant() {
        if ("CHANGE_ME_TO_A_REAL_TEST_TENANT_SLUG".equals(TEST_TENANT_SLUG)) {
            System.out.println("Skipping — set TEST_TENANT_SLUG to a real, disposable test tenant slug "
                    + "with a Phase 1 vector store already materialized (Phase 2A) before running this.");
            return;
        }

        Tenant tenant = tenantRepository.findBySlug(TEST_TENANT_SLUG)
                .orElseThrow(() -> new IllegalStateException("Test tenant not found: " + TEST_TENANT_SLUG));
        String vectorStoreId = tenant.aiKnowledgeVectorStoreId();
        if (vectorStoreId == null || vectorStoreId.isBlank()) {
            throw new IllegalStateException("Test tenant '" + TEST_TENANT_SLUG
                    + "' has no ai_knowledge_vector_store_id — run Phase 1 provisioning first.");
        }
        System.out.println("Validating retrieval for tenant '" + TEST_TENANT_SLUG
                + "', vector_store_id=" + vectorStoreId);

        for (String question : VALIDATION_QUESTIONS) {
            System.out.println("\n=== QUESTION: " + question + " ===");
            String response = aiClient.fileSearchQuery(vectorStoreId, question);
            System.out.println(response);
        }

        System.out.println("\nManually inspect the printed responses above: does the retrieved "
                + "content include the expected concept's knowledge (name/aliases/description) for "
                + "each question, and is a clearly-unrelated concept's knowledge absent where practical?");
    }
}
