package com.sei.nexus.automation.executor;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.automation.ExecutionContext;
import com.sei.nexus.automation.StepExecutor;
import com.sei.nexus.automation.VariableResolver;
import com.sei.nexus.common.NexusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IMAGE_ANALYSE node — passes a base64-encoded image to the vision model.
 *
 * Expected node config keys:
 *   question    — prompt template for the vision model
 *   imageRef    — {{variable}} path resolving to a base64 string in the context
 *   mimeType    — "image/jpeg" (default) | "image/png" | "image/webp"
 *
 * Output: String — the model's analysis text
 */
@Component
public class ImageAnalyseExecutor implements StepExecutor {

    private final AzureOpenAiClient openAi;

    public ImageAnalyseExecutor(AzureOpenAiClient openAi) {
        this.openAi = openAi;
    }

    @Override
    public String nodeType() {
        return "IMAGE_ANALYSE";
    }

    @Override
    public Object execute(String nodeId, Map<String, Object> config, ExecutionContext ctx) throws Exception {
        String questionTemplate = getString(config, "question");
        String imageRefTemplate = getString(config, "imageRef");
        String mimeType         = getString(config, "mimeType");

        if (questionTemplate == null || questionTemplate.isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST, "IMAGE_ANALYSE node '" + nodeId + "' missing question");
        if (imageRefTemplate == null || imageRefTemplate.isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST, "IMAGE_ANALYSE node '" + nodeId + "' missing imageRef");

        if (mimeType == null || mimeType.isBlank()) mimeType = "image/jpeg";

        String question   = VariableResolver.resolve(questionTemplate, ctx).toString();
        Object imageValue = VariableResolver.resolve(imageRefTemplate, ctx);
        String base64     = imageValue == null ? null : imageValue.toString();

        if (base64 == null || base64.isBlank())
            throw new NexusException(HttpStatus.BAD_REQUEST,
                    "IMAGE_ANALYSE node '" + nodeId + "': imageRef resolved to empty value");

        return openAi.analyzeImage(question, base64, mimeType,
                "You are a damage assessment specialist. Analyse the image carefully and respond concisely.");
    }

    private String getString(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : v.toString();
    }
}
