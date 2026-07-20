package com.svechka.backend.diary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, UUID> {

    boolean existsByUserIdAndDate(UUID userId, LocalDate date);

    Optional<DiaryEntry> findByIdAndUserId(UUID id, UUID userId);

    Page<DiaryEntry> findByUserIdOrderByDateDesc(UUID userId, Pageable pageable);

    List<DiaryEntry> findByUserIdAndStatusAndDateBetweenOrderByDateAsc(
            UUID userId, DiaryEntryStatus status, LocalDate startInclusive, LocalDate endInclusive);

    @Query("select distinct e.userId from DiaryEntry e where e.status = :status "
            + "and e.date between :start and :end")
    List<UUID> findDistinctUserIdsWithStatusInRange(
            DiaryEntryStatus status, LocalDate start, LocalDate end);
}
