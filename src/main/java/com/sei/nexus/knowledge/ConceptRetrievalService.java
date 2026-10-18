package com.sei.nexus.knowledge;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.LlmCallTag;
import com.sei.nexus.common.NexusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 candidate retrieval: embeds the question and returns the connection's nearest concepts
 * from the Postgres concept index, plus any concepts carried over from the previous turn.
 *
 * <p>This is the only retrieval path. It narrows by vector similarity and nothing else — which of
 * the candidates is relevant is decided by the Stage 1 LLM. If the index has not been built, or the
 * embedding call fails, a {@link NexusException} is thrown; there is no unnarrowed fallback.
 */
@Service
public class ConceptRetrievalService {

    private final AzureOpenAiClient aiClient;
    private final ConceptEmbeddingRepository repository;
    private final int topK;

    @Autowired
    public ConceptRetrievalService(AzureOpenAiClient aiClient, ConceptEmbeddingRepository repository,
                                   @Value("${nexus.concept-retrieval.top-k:20}") int topK) {
        this.aiClient   = aiClient;
        this.repository = repository;
        this.topK       = topK;
    }

    public List<ConceptEmbeddingRepository.Candidate> retrieve(String connectionKey, String query,
                                                               Collection<String> carriedConceptKeys) {
        return retrieve(connectionKey, query, carriedConceptKeys, topK);
    }

    /** The reconsideration pass: twice as many candidates. */
    public List<ConceptEmbeddingRepository.Candidate> retrieveWider(String connectionKey, String query,
                                                                    Collection<String> carriedConceptKeys) {
        return retrieve(connectionKey, query, carriedConceptKeys, topK * 2);
    }

    List<ConceptEmbeddingRepository.Candidate> retrieve(String connectionKey, String query,
                                                        Collection<String> carriedConceptKeys, int k) {
        if (repository.countForConnection(connectionKey) == 0) {
            throw new NexusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The concept index for connection '" + connectionKey
                            + "' has not been built. Run the AI Knowledge sync for this tenant.");
        }
        float[] embedding;
        try {
            LlmCallTag.set("CONCEPT_RETRIEVAL_EMBEDDING");
            embedding = aiClient.embed(query).embedding();
        } catch (Exception e) {
            throw new NexusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Concept retrieval failed to embed the question: " + e.getMessage());
        }
        Map<String, ConceptEmbeddingRepository.Candidate> merged = new LinkedHashMap<>();
        for (var c : repository.searchNearest(connectionKey, embedding, k)) merged.putIfAbsent(c.conceptKey(), c);
        for (var c : repository.findByConceptKeys(connectionKey, carriedConceptKeys)) merged.putIfAbsent(c.conceptKey(), c);
        return List.copyOf(merged.values());
    }
}
