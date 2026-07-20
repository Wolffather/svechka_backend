package com.svechka.backend.insight;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface WeeklyInsightRepository extends JpaRepository<WeeklyInsight, UUID> {

    Optional<WeeklyInsight> findByUserIdAndWeekStartDate(UUID userId, LocalDate weekStartDate);

    Page<WeeklyInsight> findByUserIdOrderByWeekStartDateDesc(UUID userId, Pageable pageable);
}
