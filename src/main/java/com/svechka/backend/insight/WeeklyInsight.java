package com.svechka.backend.insight;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "weekly_insight")
public class WeeklyInsight {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "week_start_date", nullable = false)
    private LocalDate weekStartDate;

    @Column(name = "insight_text", nullable = false)
    private String insightText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WeeklyInsight() {
    }

    public WeeklyInsight(UUID id, UUID userId, LocalDate weekStartDate, String insightText, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.weekStartDate = weekStartDate;
        this.insightText = insightText;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getWeekStartDate() {
        return weekStartDate;
    }

    public String getInsightText() {
        return insightText;
    }

    public void setInsightText(String insightText) {
        this.insightText = insightText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
