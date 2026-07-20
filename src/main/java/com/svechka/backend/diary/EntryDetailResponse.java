package com.svechka.backend.diary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EntryDetailResponse(
        UUID id,
        LocalDate date,
        String status,
        String rawTranscript,
        String aiFollowUpQuestion,
        String aiFollowUpAnswer,
        String aiSummary,
        Instant createdAt
) {

    public static EntryDetailResponse from(DiaryEntry entry) {
        return new EntryDetailResponse(
                entry.getId(),
                entry.getDate(),
                entry.getStatus().name(),
                entry.getRawTranscript(),
                entry.getAiFollowUpQuestion(),
                entry.getAiFollowUpAnswer(),
                entry.getAiSummary(),
                entry.getCreatedAt());
    }
}
