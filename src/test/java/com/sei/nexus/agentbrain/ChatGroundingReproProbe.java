package com.sei.nexus.agentbrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.enterprise.EnterpriseMapRepository;
import com.sei.nexus.semantic.BusinessLanguageResolver;
import com.sei.nexus.semantic.ResolvedQuestion;
import com.sei.nexus.semanticmodel.EnterpriseSemanticAssembler;
import com.sei.nexus.sql.SqlTableReferenceExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DIAGNOSTIC (opt-in, read-only): reproduces the exact conversational TABLE SCHEMA grounding for
 * the maryland data-analyst agent through the real Unified Answer Engine spine — AgentBrain →
 * EnterpriseSemanticAssembler → ExecutionContractBuilder → PromptContextBuilder → PromptAssembler
 * (conversational render policy). Prints the grounding and the contract's approvedAssets so we can
 * see precisely whether each table is schema-qualified. Guarded by -Dnexus.repro.grounding=true.
 */
class ChatGroundingReproProbe {

    /** A resolver that returns no resolutions — grounding table rendering is independent of it. */
    static class EmptyResolver extends BusinessLanguageResolver {
        EmptyResolver() { super(null, null, null); }
        @Override public ResolvedQuestion resolve(String q, List<String> domainKeys) {
            return ResolvedQuestion.empty(q);
        }
    }

    private static Map<String, String> env() throws Exception {
        Map<String, String> e = new java.util.HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(".env.local"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int i = line.indexOf('=');
                e.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        return e;
    }

    @Test
    void reproduceMarylandChatGrounding() throws Exception {
        assumeTrue(Boolean.getBoolean("nexus.repro.grounding"), "diagnostic disabled");
        Map<String, String> env = env();

        Matcher m = Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)")
                .matcher(env.get("NEXUS_DB_URL"));
        assumeTrue(m.find());
        String url = "jdbc:postgresql://" + m.group(1) + ":" + m.group(2) + "/" + m.group(3)
                + "?sslmode=require&currentSchema=tenant_maryland_corporations";

        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                env.get("NEXUS_DB_USERNAME"), env.get("NEXUS_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");

        EnterpriseMapRepository repo = new EnterpriseMapRepository(new JdbcTemplate(ds));
        AgentBrain brain = new AgentBrain(new EnterpriseSemanticAssembler(repo, new ObjectMapper()),
                new EmptyResolver());
        ExecutionContractBuilder builder = new ExecutionContractBuilder(new SqlTableReferenceExtractor());
        PromptContextBuilder pcb = new PromptContextBuilder();
        PromptAssembler pa = new PromptAssembler();

        String question = "What is the current inventory status of our products?";
        ResolvedBusinessModel model = brain.resolve(
                "data-analyst", List.of("conn-5780d333"), List.of("PLATFORM"), question);
        ExecutionContract contract = builder.compile(model);
        PromptContext ctx = pcb.build(contract);

        System.out.println("\n########## RESOLVED OBJECTS (ranked) ##########");
        ctx.objects().forEach(o -> System.out.println(
                "  object: schema=" + o.schema() + " table=" + o.physicalTable()
                        + " conn=" + o.connectionKey() + " attrs=" + o.attributes().size()));

        System.out.println("\n########## approvedAssets (the gate's surface) ##########");
        contract.executionBindings().approvedAssets().stream()
                .sorted(java.util.Comparator.comparing(a -> a.canonicalIdentifier()))
                .forEach(a -> System.out.println("  " + a.connectionKey() + " :: " + a.canonicalIdentifier()));

        String bounded   = pa.assemble(ctx, new PromptAssembler.RenderOptions(true, true, true, 1500));
        String unbounded = pa.assemble(ctx, new PromptAssembler.RenderOptions(true, true, true, 0));
        int boundedTables   = bounded.split("TABLE `", -1).length - 1;
        int unboundedTables = unbounded.split("TABLE `", -1).length - 1;
        System.out.println("\n########## BUDGET EFFECT ##########");
        System.out.println("  tables in contract (approved surface): " + ctx.objects().size());
        System.out.println("  tables rendered at budget=1500        : " + boundedTables);
        System.out.println("  tables rendered unbounded             : " + unboundedTables);
        System.out.println("  inventory_balances in bounded render? : " + bounded.contains("inventory_balances"));
        System.out.println("  inventory_balances in unbounded?      : "
                + unbounded.contains("retail_core.inventory_balances"));
        System.out.println("########## END ##########");
    }
}
