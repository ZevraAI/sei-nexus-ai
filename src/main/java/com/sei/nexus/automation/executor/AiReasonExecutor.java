package com.sei.nexus.automation.executor;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.ChatMessage;
import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import com.sei.nexus.automation.VariableResolver;
import com.sei.nexus.common.NexusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AI_REASON node — sends a prompt to the LLM and returns either plain text
 * or structured JSON depending on the outputFormat config.
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

    private final AzureOpenAiClient openAi;
    private final ObjectMapper       mapper;

    public AiReasonExecutor(AzureOpenAiClient openAi, ObjectMapper mapper) {
        this.openAi  = openAi;
        this.mapper  = mapper;
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

        String systemPrompt = systemTemplate == null ? null
                : VariableResolver.resolve(systemTemplate, ctx).toString();
        String userPrompt   = VariableResolver.resolve(userTemplate, ctx).toString();

        List<ChatMessage> messages = List.of(new ChatMessage("user", userPrompt));

        String raw;
        if ("json".equalsIgnoreCase(outputFormat)) {
            raw = openAi.chatWithJson(messages, systemPrompt != null ? systemPrompt : "You are a helpful assistant. Respond in JSON.");
            try {
                return mapper.readValue(raw, Map.class);
            } catch (Exception e) {
                return Map.of("raw", raw);
            }
        } else {
            raw = openAi.chat(messages, systemPrompt != null ? systemPrompt : "You are a helpful assistant.");
            return raw;
        }
    }

    private String getString(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : v.toString();
    }
}
