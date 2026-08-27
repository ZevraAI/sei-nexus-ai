package com.sei.nexus.chat;

import com.sei.nexus.common.Keys;
import com.sei.nexus.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC ONLY — not a regression test, never runs in the normal suite.
 *
 * <p>Exercises the REAL, fully-wired {@link ChatService#ask} end to end (real Spring context,
 * real datasource against the actual tenant DB, real {@code AzureOpenAiClient}) for exactly one
 * question, with {@code -Dnexus.capture.payload.dir} enabled so every OpenAI request this turn
 * makes (the routing call AND the ReasoningPlanner SQL-generation call) is dumped verbatim to
 * disk by the existing, unmodified {@code AzureOpenAiClient.capturePayload()} mechanism — no
 * reconstruction, no hand-built prompt, no new capture code.
 *
 * <p>Makes no code change to production classes. Modifies no prompt, no model parameter, no
 * SQL generation, no Runtime, no Global List. Its only effect is: (a) setting the tenant context
 * this JVM would normally get from a request filter, and (b) invoking the real bean directly
 * instead of through HTTP — avoiding the need to mint a Supabase auth token for one diagnostic
 * call. The request that reaches OpenAI is produced by 100% real, unmodified application code.
 *
 * <p>Guarded by requiring -Dnexus.capture.payload.dir to be explicitly set — absent, this test
 * is skipped, exactly like the existing opt-in live probes in this package/repo.
 */
@SpringBootTest
class ProductionRequestCaptureDiagnostic {

    @Autowired
    private ChatService chatService;

    @Test
    void captureOneRealShowMeAllOpenOrdersRequest() {
        String captureDir = System.getProperty("nexus.capture.payload.dir");
        assumeTrue(captureDir != null && !captureDir.isBlank(),
                "set -Dnexus.capture.payload.dir=<dir> to run this diagnostic");

        System.out.println("Capturing to: " + Path.of(captureDir).toAbsolutePath());

        // Matches the real failing tenant/connection exactly (from the forensic investigation).
        TenantContext.set("tenant_retail_industry");
        try {
            ChatRequest request = new ChatRequest(
                    "data-analyst",                 // agentKey — the tenant's default agent
                    Keys.conversationKey(),          // fresh conversation, same as a new UI session
                    "show me all open orders",       // exact wording, unmodified
                    null, null);

            ChatResponse response = chatService.ask(request, "prakash.stk12@gmail.com");

            System.out.println("\n########## REAL PRODUCTION ASK() COMPLETED ##########");
            System.out.println("runKey: " + response.runKey());
            System.out.println("decision: " + response.decision());
            System.out.println("answer: " + response.answer());
            if (response.reasoningSteps() != null) {
                response.reasoningSteps().forEach(s -> System.out.println("  step: " + s));
            }
        } finally {
            TenantContext.clear();
        }
    }
}
