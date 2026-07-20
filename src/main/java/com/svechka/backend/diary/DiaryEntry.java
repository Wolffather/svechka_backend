package com.svechka.backend.diary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "diary_entry")
public class DiaryEntry {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DiaryEntryStatus status;

    @Column(name = "raw_transcript", nullable = false)
    private String rawTranscript;

    @Column(name = "ai_follow_up_question")
    private String aiFollowUpQuestion;

    @Column(name = "ai_follow_up_answer")
    private String aiFollowUpAnswer;

    @Column(name = "ai_summary")
    private String aiSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DiaryEntry() {
    }

    public DiaryEntry(UUID id, UUID userId, LocalDate date, DiaryEntryStatus status,
                       String rawTranscript, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.date = date;
        this.status = status;
        this.rawTranscript = rawTranscript;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getDate() {
        return date;
    }

    public DiaryEntryStatus getStatus() {
        return status;
    }

    public void setStatus(DiaryEntryStatus status) {
        this.status = status;
    }

    public String getRawTranscript() {
        return rawTranscript;
    }

    public void setRawTranscript(String rawTranscript) {
        this.rawTranscript = rawTranscript;
    }

    public String getAiFollowUpQuestion() {
        return aiFollowUpQuestion;
    }

    public void setAiFollowUpQuestion(String aiFollowUpQuestion) {
        this.aiFollowUpQuestion = aiFollowUpQuestion;
    }

    public String getAiFollowUpAnswer() {
        return aiFollowUpAnswer;
    }

    public void setAiFollowUpAnswer(String aiFollowUpAnswer) {
        this.aiFollowUpAnswer = aiFollowUpAnswer;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
