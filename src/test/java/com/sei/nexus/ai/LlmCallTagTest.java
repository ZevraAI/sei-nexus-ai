package com.sei.nexus.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zevra Cognitive Runtime baseline — {@link LlmCallTag} is pure measurement plumbing: it labels
 * an {@link AzureOpenAiClient} call for the {@code LLM_METRIC} log line and influences nothing
 * about how the call is made or answered.
 */
class LlmCallTagTest {

    @AfterEach
    void clearBetweenTests() {
        LlmCallTag.clear();
    }

    @Test
    void defaultsToUntaggedWhenNothingHasBeenSet() {
        assertEquals("UNTAGGED", LlmCallTag.get());
    }

    @Test
    void returnsWhateverWasSet() {
        LlmCallTag.set("PLANNER");
        assertEquals("PLANNER", LlmCallTag.get());
    }

    @Test
    void clearResetsToUntagged() {
        LlmCallTag.set("EVALUATOR");
        LlmCallTag.clear();
        assertEquals("UNTAGGED", LlmCallTag.get());
    }

    @Test
    void settingATagOverwritesThePreviousOne() {
        LlmCallTag.set("STAGE1_CONCEPT_SELECTION");
        LlmCallTag.set("ANSWER_COMPOSER");
        assertEquals("ANSWER_COMPOSER", LlmCallTag.get());
    }

    @Test
    void isThreadLocalNotSharedAcrossThreads() throws InterruptedException {
        LlmCallTag.set("MAIN_THREAD_TAG");
        String[] otherThreadValue = new String[1];
        Thread t = new Thread(() -> otherThreadValue[0] = LlmCallTag.get());
        t.start();
        t.join();

        assertEquals("UNTAGGED", otherThreadValue[0],
                "a tag set on this thread must never be visible from another thread");
        assertEquals("MAIN_THREAD_TAG", LlmCallTag.get(),
                "this thread's own tag must be unaffected by the other thread reading its own (unset) value");
    }
}
