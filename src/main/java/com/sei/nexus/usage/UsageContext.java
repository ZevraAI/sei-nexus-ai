package com.sei.nexus.usage;

/**
 * Thread-local carrier for usage attribution metadata.
 * Set by service layer before any LLM call; read by AzureOpenAiClient after the call.
 */
public class UsageContext {

    public record Ctx(String feature, String userEmail, String agentName) {}

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    public static void set(String feature, String userEmail, String agentName) {
        HOLDER.set(new Ctx(feature, userEmail, agentName));
    }

    public static void set(String feature, String userEmail) {
        set(feature, userEmail, null);
    }

    public static Ctx get() { return HOLDER.get(); }

    public static void clear() { HOLDER.remove(); }
}
