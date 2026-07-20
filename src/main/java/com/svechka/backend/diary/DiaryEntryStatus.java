package com.svechka.backend.diary;

public enum DiaryEntryStatus {
    AWAITING_FOLLOW_UP,
    COMPLETE,
    /**
     * The transcript tripped a crisis-signal screen (self-harm, suicidal ideation, acute
     * despair). The normal follow-up/summary pipeline never ran; aiSummary holds a calm,
     * non-clinical response instead. Deliberately excluded from weekly-insight aggregation.
     */
    CRISIS_SUPPORT
}
