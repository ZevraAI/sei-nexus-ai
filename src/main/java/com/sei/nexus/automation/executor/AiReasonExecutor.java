package com.sei.nexus.automation.executor;

import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import com.sei.nexus.automation.VariableResolver;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.response.NaturalLanguageComposer;
import com.sei.nexus.response.NaturalLanguageComposer.ResponseMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI_REASON node — sends a prompt to the LLM and returns either plain text
 * or structured JSON depending on the outputFormat config.
 *
 * <p>Unified Answer Engine, Phase 4: the model-call mechanics (message assembly + text/JSON mode
 * select) are delegated to the shared {@link NaturalLanguageComposer}. This executor owns only its
 * node policy — variable templating, the default system prompts, the JSON→Map parsing, and the
 * propagate-on-failure semantics (no answer fallback; a failed step fails).
 *
 * Expected node config keys:
 *   systemPrompt   — system message template (optional)
 *   userPrompt     — user message template with {{variable}} placeholders
 *   outputFormat   — "text" (default) | "json"
 *
 * Output:
 *   "text"  → String
 *   "json"  → Map<String,Object> (parsed from LLM JSON response)
 */
@Component
public class AiReasonExecutor implements StepExecutor {

    private final NaturalLanguageComposer nlComposer;
    private final ObjectMapper            mapper;

    public AiReasonExecutor(NaturalLanguageComposer nlComposer, ObjectMapper mapper) {
        this.nlComposer = nlComposer;
        this.mapper     = mapper;
    }

    @Override
    public String nodeType() {
        return "AI_REASON";
    }

    @Override
    public Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) throws Exception {
        String systemTemplate = getString(config, "systemPrompt");
        String userTemplate   = getString(config, "userPrompt");
        String outputFormat   = getString(config, "outputFormat");
        if (outputFormat == null || outputFormat.isBlank()) outputFormat = "text";

        if (userTemplate == null || userTemplate.isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST, "AI_REASON node '" + nodeId + "' missing userPrompt");

        String resolvedSystem = systemTemplate == null ? null
                : VariableResolver.resolve(systemTemplate, ctx).toString();
        String userPrompt     = VariableResolver.resolve(userTemplate, ctx).toString();

        boolean json = "json".equalsIgnoreCase(outputFormat);
        ResponseMode mode = json ? ResponseMode.JSON : ResponseMode.TEXT;
        String systemPrompt = resolvedSystem != null ? resolvedSystem
                : (json ? "You are a helpful assistant. Respond in JSON." : "You are a helpful assistant.");

        // A failed AI step must fail the automation, so compose with no fallback (propagate).
        String raw = nlComposer.compose(
                NaturalLanguageComposer.CompositionRequest.propagating(userPrompt, systemPrompt, mode));

        if (json) {
            try {
                return mapper.readValue(raw, Map.class);
            } catch (Exception e) {
                return Map.of("raw", raw);
            }
        }
        return raw;
    }

    private String getString(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : v.toString();
    }
}
