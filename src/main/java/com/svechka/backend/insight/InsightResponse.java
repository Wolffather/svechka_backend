package com.svechka.backend.insight;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InsightResponse(UUID id, LocalDate weekStartDate, String insightText, Instant createdAt) {

    public static InsightResponse from(WeeklyInsight insight) {
        return new InsightResponse(insight.getId(), insight.getWeekStartDate(), insight.getInsightText(),
                insight.getCreatedAt());
    }
}
