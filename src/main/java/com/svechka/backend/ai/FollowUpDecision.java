package com.svechka.backend.ai;

/**
 * Result of a single combined LLM call that both decides whether a clarifying follow-up
 * question is warranted and screens the transcript for crisis signals (self-harm, suicidal
 * ideation, acute despair) — done in one round trip to keep the pipeline within its latency
 * budget. When {@code crisisDetected} is true, {@code question} is always null: the normal
 * follow-up/summary pipeline must not run, and {@code crisisMessage} should be shown instead.
 */
public record FollowUpDecision(String question, boolean crisisDetected, String crisisMessage) {

    public static FollowUpDecision none() {
        return new FollowUpDecision(null, false, null);
    }
}
