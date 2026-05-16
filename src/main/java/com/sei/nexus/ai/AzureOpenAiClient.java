package com.sei.nexus.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.common.NexusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AzureOpenAiClient {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.openai.com/v1";

    @Value("${nexus.openai.api-key:}")
    private String apiKey;

    @Value("${nexus.openai.chat-model:gpt-4o}")
    private String chatModel;

    @Value("${nexus.openai.embedding-model:text-embedding-ada-002}")
    private String embeddingModel;

    public AzureOpenAiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Embeds the given text using Azure OpenAI embeddings deployment.
     * Returns a float[] of the embedding vector.
     */
    public EmbeddingResult embed(String text) {
        String url = BASE_URL + "/embeddings";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", embeddingModel);
        requestBody.put("input", text);

        String responseBody = executeWithRetry(url, requestBody);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingArray = root.path("data").get(0).path("embedding");
            float[] embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = (float) embeddingArray.get(i).asDouble();
            }
            int tokenCount = root.path("usage").path("total_tokens").asInt(0);
            return new EmbeddingResult(embedding, tokenCount);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse embedding response: " + e.getMessage());
        }
    }

    /**
     * Sends a chat completion request and returns the assistant's text response.
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, false);
    }

    /**
     * Sends a chat completion request with JSON response format enabled.
     * Returns the assistant's content as a raw JSON string.
     */
    public String chatWithJson(List<ChatMessage> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, true);
    }

    private String doChat(List<ChatMessage> messages, String systemPrompt, boolean jsonMode) {
        String url = BASE_URL + "/chat/completions";

        List<Map<String, String>> messageList = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            Map<String, String> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messageList.add(sysMsg);
        }
        for (ChatMessage msg : messages) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.role());
            m.put("content", msg.content());
            messageList.add(m);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", chatModel);
        requestBody.put("messages", messageList);
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 4096);

        if (jsonMode) {
            Map<String, String> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);
        }

        String responseBody = executeWithRetry(url, requestBody);

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse chat response: " + e.getMessage());
        }
    }

    private String executeWithRetry(String url, Map<String, Object> requestBody) {
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize OpenAI request: " + e.getMessage());
        }

        long backoffMs = INITIAL_BACKOFF_MS;
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    return response.body();
                }

                if (statusCode == 429) {
                    // Rate limit — retry with exponential backoff
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                        continue;
                    }
                    throw new NexusException(HttpStatus.TOO_MANY_REQUESTS,
                            "OpenAI rate limit exceeded after " + MAX_RETRIES + " retries");
                }

                String errorBody = response.body();
                throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "OpenAI call failed: HTTP " + statusCode + " - " + errorBody);

            } catch (NexusException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "OpenAI call interrupted");
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "OpenAI call interrupted");
                    }
                }
            }
        }

        String reason = lastException != null ? lastException.getMessage() : "unknown error";
        throw new NexusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "OpenAI call failed: " + reason);
    }
}
