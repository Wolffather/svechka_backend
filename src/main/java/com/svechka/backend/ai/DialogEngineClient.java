package com.svechka.backend.ai;

import java.util.List;

/**
 * Single entry point for all LLM dialogue interactions. The rest of the application only
 * depends on this interface, never on a specific provider.
 */
public interface DialogEngineClient {

    /**
     * Screens the transcript for crisis signals and, if none are found, decides whether a
     * short clarifying question is warranted. See {@link FollowUpDecision}.
     */
    FollowUpDecision decideFollowUp(String transcript);

    /**
     * Produces a 2-3 sentence summary of the day from the given text (transcript, optionally
     * combined with a follow-up answer).
     */
    String summarizeDay(String text);

    /**
     * Produces a short weekly retrospective from a user's daily summaries, ordered oldest first.
     */
    String buildWeeklyInsight(List<String> dailySummaries);
}
