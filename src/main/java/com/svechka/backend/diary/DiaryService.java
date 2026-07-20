package com.svechka.backend.diary;

import com.svechka.backend.ai.DialogEngineClient;
import com.svechka.backend.ai.FollowUpDecision;
import com.svechka.backend.ai.TranscriptionClient;
import com.svechka.backend.common.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class DiaryService {

    private static final Logger log = LoggerFactory.getLogger(DiaryService.class);

    /**
     * Below this word count a recording is "too short" (TZ section 1) and gets a soft
     * invitation to continue instead of the normal pipeline, unless the model already
     * proposed its own follow-up question or flagged a crisis.
     */
    private static final int SHORT_ENTRY_WORD_THRESHOLD = 15;
    private static final String SHORT_ENTRY_INVITATION =
            "Похоже, коротко сегодня. Хочешь добавить что-то ещё, или на сегодня хватит?";

    private final DiaryEntryRepository repository;
    private final TranscriptionClient transcriptionClient;
    private final DialogEngineClient dialogEngineClient;
    private final TempAudioFileService tempAudioFileService;

    public DiaryService(DiaryEntryRepository repository, TranscriptionClient transcriptionClient,
                         DialogEngineClient dialogEngineClient, TempAudioFileService tempAudioFileService) {
        this.repository = repository;
        this.transcriptionClient = transcriptionClient;
        this.dialogEngineClient = dialogEngineClient;
        this.tempAudioFileService = tempAudioFileService;
    }

    public EntryCreateResponse createEntry(UUID userId, LocalDate date, MultipartFile audio) {
        log.info("createEntry called: user={}, date={}, audioSize={}", userId, date, audio.getSize());
        if (repository.existsByUserIdAndDate(userId, date)) {
            throw new DuplicateEntryException();
        }

        String transcript = transcribeAndCleanUp(audio);
        FollowUpDecision decision = dialogEngineClient.decideFollowUp(transcript);

        if (decision.crisisDetected()) {
            DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date,
                    DiaryEntryStatus.CRISIS_SUPPORT, transcript, Instant.now());
            entry.setAiSummary(decision.crisisMessage());
            repository.save(entry);
            return new EntryCreateResponse(entry.getId(), null, decision.crisisMessage(), entry.getStatus().name());
        }

        String question = decision.question();
        if (question == null && isTooShort(transcript)) {
            question = SHORT_ENTRY_INVITATION;
        }

        if (question != null) {
            DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date,
                    DiaryEntryStatus.AWAITING_FOLLOW_UP, transcript, Instant.now());
            entry.setAiFollowUpQuestion(question);
            repository.save(entry);
            return new EntryCreateResponse(entry.getId(), question, null, entry.getStatus().name());
        }

        String summary = dialogEngineClient.summarizeDay(transcript);
        DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date,
                DiaryEntryStatus.COMPLETE, transcript, Instant.now());
        entry.setAiSummary(summary);
        repository.save(entry);
        return new EntryCreateResponse(entry.getId(), null, summary, entry.getStatus().name());
    }

    private static boolean isTooShort(String transcript) {
        return transcript.trim().split("\\s+").length < SHORT_ENTRY_WORD_THRESHOLD;
    }

    public FollowUpResponse submitFollowUp(UUID userId, UUID entryId, String answerText, MultipartFile answerAudio) {
        DiaryEntry entry = repository.findByIdAndUserId(entryId, userId).orElseThrow(EntryNotFoundException::new);
        if (entry.getStatus() != DiaryEntryStatus.AWAITING_FOLLOW_UP) {
            throw new InvalidEntryStateException();
        }

        String answer = (answerText != null && !answerText.isBlank())
                ? answerText
                : (answerAudio != null && !answerAudio.isEmpty() ? transcribeAndCleanUp(answerAudio) : "");

        entry.setAiFollowUpAnswer(answer);
        String combined = entry.getRawTranscript() + "\n\nОтвет на уточняющий вопрос: " + answer;
        String summary = dialogEngineClient.summarizeDay(combined);
        entry.setAiSummary(summary);
        entry.setStatus(DiaryEntryStatus.COMPLETE);
        repository.save(entry);

        return new FollowUpResponse(entry.getId(), summary, entry.getStatus().name());
    }

    public PageResponse<EntrySummaryResponse> listEntries(UUID userId, int page, int size) {
        Page<DiaryEntry> result = repository.findByUserIdOrderByDateDesc(userId, PageRequest.of(page, size));
        return PageResponse.of(result.map(EntrySummaryResponse::from));
    }

    public EntryDetailResponse getEntry(UUID userId, UUID entryId) {
        DiaryEntry entry = repository.findByIdAndUserId(entryId, userId).orElseThrow(EntryNotFoundException::new);
        return EntryDetailResponse.from(entry);
    }

    private String transcribeAndCleanUp(MultipartFile audio) {
        Path temp = tempAudioFileService.store(audio);
        String transcript;
        try {
            transcript = transcriptionClient.transcribe(temp, audio.getOriginalFilename());
        } finally {
            tempAudioFileService.delete(temp);
        }
        if (transcript == null || transcript.isBlank()) {
            throw new UnreadableTranscriptException();
        }
        return transcript;
    }
}
