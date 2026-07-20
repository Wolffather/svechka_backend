package com.svechka.backend.insight;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

@Component
public class InsightScheduler {

    private final InsightService insightService;

    public InsightScheduler(InsightService insightService) {
        this.insightService = insightService;
    }

    /**
     * Sunday 22:00 server time: builds the retrospective for the week that just ended
     * (Monday through today).
     */
    @Scheduled(cron = "0 0 22 * * SUN")
    public void runWeeklyInsightJob() {
        LocalDate weekStartDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        insightService.generateWeeklyInsightsFor(weekStartDate);
    }
}
