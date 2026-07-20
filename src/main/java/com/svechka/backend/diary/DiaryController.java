package com.svechka.backend.diary;

import com.svechka.backend.common.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/entries")
public class DiaryController {

    private final DiaryService diaryService;

    public DiaryController(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<EntryCreateResponse> createEntry(
            @AuthenticationPrincipal UUID userId,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(diaryService.createEntry(userId, date, audio));
    }

    @PostMapping(path = "/{id}/follow-up", consumes = "multipart/form-data")
    public ResponseEntity<FollowUpResponse> submitFollowUp(
            @AuthenticationPrincipal UUID userId,
            @PathVariable("id") UUID entryId,
            @RequestParam(value = "answerText", required = false) String answerText,
            @RequestParam(value = "audio", required = false) MultipartFile answerAudio) {
        return ResponseEntity.ok(diaryService.submitFollowUp(userId, entryId, answerText, answerAudio));
    }

    @GetMapping
    public ResponseEntity<PageResponse<EntrySummaryResponse>> listEntries(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(diaryService.listEntries(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EntryDetailResponse> getEntry(
            @AuthenticationPrincipal UUID userId,
            @PathVariable("id") UUID entryId) {
        return ResponseEntity.ok(diaryService.getEntry(userId, entryId));
    }
}
