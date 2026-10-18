package com.sei.nexus.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.ai.EmbeddingResult;
import com.sei.nexus.common.NexusException;
import com.sei.nexus.knowledge.ConceptEmbeddingRepository.Candidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConceptRetrievalService}: nearest-K from the index plus the carried concepts, deduplicated
 * in a deterministic order; an unbuilt index or a failed embedding is an exception, never a silent
 * unnarrowed result. Hand-rolled fakes; no Mockito, no DB, no live OpenAI.
 */
class ConceptRetrievalServiceTest {

    static class FakeClient extends AzureOpenAiClient {
        final List<String> embedded = new ArrayList<>();
        RuntimeException failure;
        FakeClient() { super(new ObjectMapper(), null); }
        @Override public EmbeddingResult embed(String text) {
            if (failure != null) throw failure;
            embedded.add(text);
            return new EmbeddingResult(new float[] {0.5f, 0.5f}, 4);
        }
    }

    static class FakeRepo extends ConceptEmbeddingRepository {
        int indexed = 5;
        List<Candidate> nearest = List.of();
        List<Candidate> carriedRows = List.of();
        final List<Integer> topKs = new ArrayList<>();
        final List<Collection<String>> carriedLookups = new ArrayList<>();
        FakeRepo() { super(null); }
        @Override public int countForConnection(String connectionKey) { return indexed; }
        @Override public List<Candidate> searchNearest(String connectionKey, float[] queryEmbedding, int topK) {
            topKs.add(topK);
            return nearest;
        }
        @Override public List<Candidate> findByConceptKeys(String connectionKey, Collection<String> conceptKeys) {
            carriedLookups.add(conceptKeys);
            return carriedRows;
        }
    }

    private static Candidate c(String key, double sim) { return new Candidate(key, key + " doc", sim); }

    @Test
    void returnsTheNearestConceptsInIndexOrderPlusCarriedOnesWithoutDuplicates() {
        FakeClient client = new FakeClient();
        FakeRepo repo = new FakeRepo();
        repo.nearest = List.of(c("alpha", 0.9), c("beta", 0.8));
        repo.carriedRows = List.of(c("beta", 0.0), c("gamma", 0.0));
        var service = new ConceptRetrievalService(client, repo, 20);

        var result = service.retrieve("conn-1", "the question", List.of("beta", "gamma"));

        assertEquals(List.of("alpha", "beta", "gamma"), result.stream().map(Candidate::conceptKey).toList());
        assertEquals(0.8, result.get(1).similarity(), "the real similarity wins over the carried 0");
        assertEquals(List.of("the question"), client.embedded);
        assertEquals(List.of(20), repo.topKs);
    }

    @Test
    void theWiderRetrievalAsksForTwiceAsMany() {
        FakeRepo repo = new FakeRepo();
        new ConceptRetrievalService(new FakeClient(), repo, 20).retrieveWider("conn-1", "q", List.of());
        assertEquals(List.of(40), repo.topKs);
    }

    @Test
    void anUnbuiltIndexIsAnExceptionAndNoEmbeddingIsSpent() {
        FakeClient client = new FakeClient();
        FakeRepo repo = new FakeRepo();
        repo.indexed = 0;

        NexusException e = assertThrows(NexusException.class,
                () -> new ConceptRetrievalService(client, repo, 20).retrieve("conn-1", "q", List.of()));

        assertTrue(e.getMessage().contains("has not been built"));
        assertTrue(client.embedded.isEmpty());
    }

    @Test
    void aFailedEmbeddingIsAnExceptionNotAnEmptyResult() {
        FakeClient client = new FakeClient();
        client.failure = new IllegalStateException("embedding service down");

        NexusException e = assertThrows(NexusException.class,
                () -> new ConceptRetrievalService(client, new FakeRepo(), 20).retrieve("conn-1", "q", List.of()));

        assertTrue(e.getMessage().contains("embedding service down"));
    }

    @Test
    void theSameInputsGiveTheSameOutput() {
        FakeRepo repo = new FakeRepo();
        repo.nearest = List.of(c("alpha", 0.9), c("beta", 0.8));
        var service = new ConceptRetrievalService(new FakeClient(), repo, 20);

        assertEquals(service.retrieve("conn-1", "q", List.of()), service.retrieve("conn-1", "q", List.of()));
    }
}
