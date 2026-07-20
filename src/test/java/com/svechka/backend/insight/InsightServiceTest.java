package com.svechka.backend.insight;

import com.svechka.backend.ai.DialogEngineClient;
import com.svechka.backend.diary.DiaryEntry;
import com.svechka.backend.diary.DiaryEntryRepository;
import com.svechka.backend.diary.DiaryEntryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    @Mock
    private WeeklyInsightRepository weeklyInsightRepository;
    @Mock
    private DiaryEntryRepository diaryEntryRepository;
    @Mock
    private DialogEngineClient dialogEngineClient;

    private InsightService service;

    private final LocalDate weekStart = LocalDate.of(2026, 7, 13);
    private final LocalDate weekEnd = weekStart.plusDays(6);

    @BeforeEach
    void setUp() {
        service = new InsightService(weeklyInsightRepository, diaryEntryRepository, dialogEngineClient);
    }

    private DiaryEntry completeEntry(UUID userId, LocalDate date, String summary) {
        DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date, DiaryEntryStatus.COMPLETE,
                "транскрипт", Instant.now());
        entry.setAiSummary(summary);
        return entry;
    }

    @Test
    void createsInsightForUserWithEntriesThatWeek() {
        UUID userId = UUID.randomUUID();
        when(diaryEntryRepository.findDistinctUserIdsWithStatusInRange(DiaryEntryStatus.COMPLETE, weekStart, weekEnd))
                .thenReturn(List.of(userId));
        when(diaryEntryRepository.findByUserIdAndStatusAndDateBetweenOrderByDateAsc(
                userId, DiaryEntryStatus.COMPLETE, weekStart, weekEnd))
                .thenReturn(List.of(completeEntry(userId, weekStart, "День 1"), completeEntry(userId, weekStart.plusDays(1), "День 2")));
        when(dialogEngineClient.buildWeeklyInsight(anyList())).thenReturn("Наблюдение за неделю.");
        when(weeklyInsightRepository.findByUserIdAndWeekStartDate(userId, weekStart)).thenReturn(Optional.empty());

        service.generateWeeklyInsightsFor(weekStart);

        ArgumentCaptor<WeeklyInsight> captor = ArgumentCaptor.forClass(WeeklyInsight.class);
        verify(weeklyInsightRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getWeekStartDate()).isEqualTo(weekStart);
        assertThat(captor.getValue().getInsightText()).isEqualTo("Наблюдение за неделю.");
    }

    @Test
    void skipsUserWithNoEntriesThatWeek() {
        UUID userId = UUID.randomUUID();
        when(diaryEntryRepository.findDistinctUserIdsWithStatusInRange(DiaryEntryStatus.COMPLETE, weekStart, weekEnd))
                .thenReturn(List.of(userId));
        when(diaryEntryRepository.findByUserIdAndStatusAndDateBetweenOrderByDateAsc(
                userId, DiaryEntryStatus.COMPLETE, weekStart, weekEnd))
                .thenReturn(List.of());

        service.generateWeeklyInsightsFor(weekStart);

        verify(dialogEngineClient, never()).buildWeeklyInsight(anyList());
        verify(weeklyInsightRepository, never()).save(any());
    }

    @Test
    void reRunningForSameWeekUpdatesExistingInsightInsteadOfDuplicating() {
        UUID userId = UUID.randomUUID();
        WeeklyInsight existing = new WeeklyInsight(UUID.randomUUID(), userId, weekStart, "Старый текст", Instant.now());
        when(diaryEntryRepository.findDistinctUserIdsWithStatusInRange(DiaryEntryStatus.COMPLETE, weekStart, weekEnd))
                .thenReturn(List.of(userId));
        when(diaryEntryRepository.findByUserIdAndStatusAndDateBetweenOrderByDateAsc(
                userId, DiaryEntryStatus.COMPLETE, weekStart, weekEnd))
                .thenReturn(List.of(completeEntry(userId, weekStart, "День 1")));
        when(dialogEngineClient.buildWeeklyInsight(anyList())).thenReturn("Новый текст");
        when(weeklyInsightRepository.findByUserIdAndWeekStartDate(userId, weekStart)).thenReturn(Optional.of(existing));

        service.generateWeeklyInsightsFor(weekStart);

        ArgumentCaptor<WeeklyInsight> captor = ArgumentCaptor.forClass(WeeklyInsight.class);
        verify(weeklyInsightRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getInsightText()).isEqualTo("Новый текст");
    }

    @Test
    void oneUsersFailureDoesNotBlockTheRestOfTheBatch() {
        UUID failingUserId = UUID.randomUUID();
        UUID okUserId = UUID.randomUUID();
        when(diaryEntryRepository.findDistinctUserIdsWithStatusInRange(DiaryEntryStatus.COMPLETE, weekStart, weekEnd))
                .thenReturn(List.of(failingUserId, okUserId));
        when(diaryEntryRepository.findByUserIdAndStatusAndDateBetweenOrderByDateAsc(
                eq(failingUserId), eq(DiaryEntryStatus.COMPLETE), eq(weekStart), eq(weekEnd)))
                .thenReturn(List.of(completeEntry(failingUserId, weekStart, "День 1")));
        when(diaryEntryRepository.findByUserIdAndStatusAndDateBetweenOrderByDateAsc(
                eq(okUserId), eq(DiaryEntryStatus.COMPLETE), eq(weekStart), eq(weekEnd)))
                .thenReturn(List.of(completeEntry(okUserId, weekStart, "День 1")));
        when(dialogEngineClient.buildWeeklyInsight(anyList()))
                .thenThrow(new RuntimeException("AI provider down"))
                .thenReturn("Наблюдение за неделю.");
        when(weeklyInsightRepository.findByUserIdAndWeekStartDate(any(), eq(weekStart))).thenReturn(Optional.empty());

        service.generateWeeklyInsightsFor(weekStart);

        ArgumentCaptor<WeeklyInsight> captor = ArgumentCaptor.forClass(WeeklyInsight.class);
        verify(weeklyInsightRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(okUserId);
    }
}
