package com.svechka.backend.diary;

import java.time.LocalDate;
import java.util.UUID;

public record EntrySummaryResponse(UUID id, LocalDate date, String status, String summary) {

    public static EntrySummaryResponse from(DiaryEntry entry) {
        return new EntrySummaryResponse(entry.getId(), entry.getDate(), entry.getStatus().name(),
                entry.getAiSummary());
    }
}
