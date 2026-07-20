package com.svechka.backend.insight;

import com.svechka.backend.ai.DialogEngineClient;
import com.svechka.backend.common.PageResponse;
import com.svechka.backend.diary.DiaryEntry;
import com.svechka.backend.diary.DiaryEntryRepository;
import com.svechka.backend.diary.DiaryEntryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private final WeeklyInsightRepository weeklyInsightRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final DialogEngineClient dialogEngineClient;

    public InsightService(WeeklyInsightRepository weeklyInsightRepository, DiaryEntryRepository diaryEntryRepository,
                           DialogEngineClient dialogEngineClient) {
        this.weeklyInsightRepository = weeklyInsightRepository;
        this.diaryEntryRepository = diaryEntryRepository;
        this.dialogEngineClient = dialogEngineClient;
    }

    /**
     * Idempotent: safe to run repeatedly for the same week, updates rather than duplicates.
     * One user's failure is logged and does not stop the rest of the batch.
     */
    public void generateWeeklyInsightsFor(LocalDate weekStartDate) {
        LocalDate weekEndDate = weekStartDate.plusDays(6);
        List<UUID> userIds = diaryEntryRepository.findDistinctUserIdsWithStatusInRange(
                DiaryEntryStatus.COMPLETE, weekStartDate, weekEndDate);

        for (UUID userId : userIds) {
            try {
                generateForUser(userId, weekStartDate, weekEndDate);
            } catch (Exception e) {
                log.error("Failed to generate weekly insight for user {} week {}", userId, weekStartDate, e);
            }
        }
    }

    private void generateForUser(UUID userId, LocalDate weekStartDate, LocalDate weekEndDate) {
        List<DiaryEntry> entries = diaryEntryRepository.findByUserIdAndStatusAndDateBetweenOrderByDateAsc(
                userId, DiaryEntryStatus.COMPLETE, weekStartDate, weekEndDate);
        if (entries.isEmpty()) {
            return;
        }

        List<String> summaries = entries.stream().map(DiaryEntry::getAiSummary).toList();
        String insightText = dialogEngineClient.buildWeeklyInsight(summaries);

        WeeklyInsight insight = weeklyInsightRepository.findByUserIdAndWeekStartDate(userId, weekStartDate)
                .orElseGet(() -> new WeeklyInsight(UUID.randomUUID(), userId, weekStartDate, insightText,
                        Instant.now()));
        insight.setInsightText(insightText);
        weeklyInsightRepository.save(insight);
    }

    public PageResponse<InsightResponse> listInsights(UUID userId, int page, int size) {
        Page<WeeklyInsight> result = weeklyInsightRepository.findByUserIdOrderByWeekStartDateDesc(
                userId, PageRequest.of(page, size));
        return PageResponse.of(result.map(InsightResponse::from));
    }
}
