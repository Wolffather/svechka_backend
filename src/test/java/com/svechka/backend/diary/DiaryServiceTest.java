package com.svechka.backend.diary;

import com.svechka.backend.ai.DialogEngineClient;
import com.svechka.backend.ai.FollowUpDecision;
import com.svechka.backend.ai.TranscriptionClient;
import com.svechka.backend.common.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiaryServiceTest {

    @Mock
    private DiaryEntryRepository repository;
    @Mock
    private TranscriptionClient transcriptionClient;
    @Mock
    private DialogEngineClient dialogEngineClient;
    @Mock
    private TempAudioFileService tempAudioFileService;

    private DiaryService service;
    private final UUID userId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 7, 19);

    // 19 words — deliberately at/above the "too short" threshold so tests about the normal
    // follow-up/summary path aren't accidentally short-circuited by the short-entry check.
    private static final String LONG_TRANSCRIPT =
            "Сегодня был хороший день, я гулял в парке, встретил старого друга и мы долго "
                    + "разговаривали о работе, планах на будущее и о том, как прошёл этот месяц";

    @BeforeEach
    void setUp() {
        service = new DiaryService(repository, transcriptionClient, dialogEngineClient, tempAudioFileService);
    }

    private MockMultipartFile audioFile() {
        return new MockMultipartFile("audio", "recording.webm", "audio/webm", new byte[] {1, 2, 3});
    }

    @Test
    void createEntryWithoutFollowUpSavesCompleteEntry() {
        Path tempPath = Path.of("/tmp/fake-audio");
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(false);
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString())).thenReturn(LONG_TRANSCRIPT);
        when(dialogEngineClient.decideFollowUp(LONG_TRANSCRIPT)).thenReturn(FollowUpDecision.none());
        when(dialogEngineClient.summarizeDay(LONG_TRANSCRIPT)).thenReturn("Краткое резюме дня.");

        EntryCreateResponse response = service.createEntry(userId, date, audioFile());

        assertThat(response.followUpQuestion()).isNull();
        assertThat(response.summary()).isEqualTo("Краткое резюме дня.");
        assertThat(response.status()).isEqualTo("COMPLETE");

        ArgumentCaptor<DiaryEntry> captor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DiaryEntryStatus.COMPLETE);
        assertThat(captor.getValue().getAiSummary()).isEqualTo("Краткое резюме дня.");
        verify(tempAudioFileService).delete(tempPath);
    }

    @Test
    void createEntryWithFollowUpSavesAwaitingFollowUpEntry() {
        Path tempPath = Path.of("/tmp/fake-audio");
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(false);
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString())).thenReturn(LONG_TRANSCRIPT);
        when(dialogEngineClient.decideFollowUp(LONG_TRANSCRIPT))
                .thenReturn(new FollowUpDecision("С кем была встреча?", false, null));

        EntryCreateResponse response = service.createEntry(userId, date, audioFile());

        assertThat(response.followUpQuestion()).isEqualTo("С кем была встреча?");
        assertThat(response.summary()).isNull();
        assertThat(response.status()).isEqualTo("AWAITING_FOLLOW_UP");
        verify(dialogEngineClient, never()).summarizeDay(anyString());

        ArgumentCaptor<DiaryEntry> captor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DiaryEntryStatus.AWAITING_FOLLOW_UP);
        assertThat(captor.getValue().getAiFollowUpQuestion()).isEqualTo("С кем была встреча?");
    }

    @Test
    void createEntryWithCrisisFlagSkipsNormalPipelineAndSavesCrisisSupportEntry() {
        Path tempPath = Path.of("/tmp/fake-audio");
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(false);
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString())).thenReturn(LONG_TRANSCRIPT);
        when(dialogEngineClient.decideFollowUp(LONG_TRANSCRIPT))
                .thenReturn(new FollowUpDecision(null, true, "Спасибо, что рассказал об этом."));

        EntryCreateResponse response = service.createEntry(userId, date, audioFile());

        assertThat(response.followUpQuestion()).isNull();
        assertThat(response.summary()).isEqualTo("Спасибо, что рассказал об этом.");
        assertThat(response.status()).isEqualTo("CRISIS_SUPPORT");
        verify(dialogEngineClient, never()).summarizeDay(anyString());

        ArgumentCaptor<DiaryEntry> captor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DiaryEntryStatus.CRISIS_SUPPORT);
        assertThat(captor.getValue().getAiSummary()).isEqualTo("Спасибо, что рассказал об этом.");
    }

    @Test
    void createEntryWithShortTranscriptAndNoModelQuestionUsesSoftInvitation() {
        Path tempPath = Path.of("/tmp/fake-audio");
        String shortTranscript = "Всё нормально, ничего особенного";
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(false);
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString())).thenReturn(shortTranscript);
        when(dialogEngineClient.decideFollowUp(shortTranscript)).thenReturn(FollowUpDecision.none());

        EntryCreateResponse response = service.createEntry(userId, date, audioFile());

        assertThat(response.status()).isEqualTo("AWAITING_FOLLOW_UP");
        assertThat(response.followUpQuestion())
                .isEqualTo("Похоже, коротко сегодня. Хочешь добавить что-то ещё, или на сегодня хватит?");
        verify(dialogEngineClient, never()).summarizeDay(anyString());
    }

    @Test
    void createEntryWithShortTranscriptButModelQuestionUsesModelQuestionNotInvitation() {
        Path tempPath = Path.of("/tmp/fake-audio");
        String shortTranscript = "Было тяжело сегодня";
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(false);
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString())).thenReturn(shortTranscript);
        when(dialogEngineClient.decideFollowUp(shortTranscript))
                .thenReturn(new FollowUpDecision("Что именно было тяжело?", false, null));

        EntryCreateResponse response = service.createEntry(userId, date, audioFile());

        assertThat(response.followUpQuestion()).isEqualTo("Что именно было тяжело?");
    }

    @Test
    void createEntryOnExistingDateThrowsConflictWithoutCallingAi() {
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(true);

        assertThatThrownBy(() -> service.createEntry(userId, date, audioFile()))
                .isInstanceOf(DuplicateEntryException.class);

        verify(transcriptionClient, never()).transcribe(any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void createEntryWithBlankTranscriptThrowsUnreadableTranscriptException() {
        Path tempPath = Path.of("/tmp/fake-audio");
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(false);
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString())).thenReturn("   ");

        assertThatThrownBy(() -> service.createEntry(userId, date, audioFile()))
                .isInstanceOf(UnreadableTranscriptException.class);

        verify(tempAudioFileService).delete(tempPath);
        verify(dialogEngineClient, never()).decideFollowUp(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void tempAudioFileIsDeletedEvenWhenTranscriptionFails() {
        Path tempPath = Path.of("/tmp/fake-audio");
        when(repository.existsByUserIdAndDate(userId, date)).thenReturn(false);
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString()))
                .thenThrow(new RuntimeException("provider unavailable"));

        assertThatThrownBy(() -> service.createEntry(userId, date, audioFile()))
                .isInstanceOf(RuntimeException.class);

        verify(tempAudioFileService).delete(tempPath);
        verify(repository, never()).save(any());
    }

    @Test
    void submitFollowUpWithTextAnswerCompletesEntry() {
        DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date, DiaryEntryStatus.AWAITING_FOLLOW_UP,
                "Был на встрече", java.time.Instant.now());
        entry.setAiFollowUpQuestion("С кем была встреча?");
        when(repository.findByIdAndUserId(entry.getId(), userId)).thenReturn(Optional.of(entry));
        when(dialogEngineClient.summarizeDay(anyString())).thenReturn("Резюме с учётом ответа.");

        FollowUpResponse response = service.submitFollowUp(userId, entry.getId(), "С коллегой Ирой", null);

        assertThat(response.summary()).isEqualTo("Резюме с учётом ответа.");
        assertThat(response.status()).isEqualTo("COMPLETE");
        assertThat(entry.getAiFollowUpAnswer()).isEqualTo("С коллегой Ирой");
        verify(repository).save(entry);
        verify(transcriptionClient, never()).transcribe(any(), anyString());
    }

    @Test
    void submitFollowUpWithAudioAnswerTranscribesFirst() {
        DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date, DiaryEntryStatus.AWAITING_FOLLOW_UP,
                "Был на встрече", java.time.Instant.now());
        Path tempPath = Path.of("/tmp/fake-answer-audio");
        when(repository.findByIdAndUserId(entry.getId(), userId)).thenReturn(Optional.of(entry));
        when(tempAudioFileService.store(any())).thenReturn(tempPath);
        when(transcriptionClient.transcribe(eq(tempPath), anyString())).thenReturn("С коллегой Ирой");
        when(dialogEngineClient.summarizeDay(anyString())).thenReturn("Резюме с учётом ответа.");

        FollowUpResponse response = service.submitFollowUp(userId, entry.getId(), null, audioFile());

        assertThat(response.summary()).isEqualTo("Резюме с учётом ответа.");
        assertThat(entry.getAiFollowUpAnswer()).isEqualTo("С коллегой Ирой");
        verify(tempAudioFileService).delete(tempPath);
    }

    @Test
    void submitFollowUpOnCompleteEntryThrowsConflict() {
        DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date, DiaryEntryStatus.COMPLETE,
                "Был на встрече", java.time.Instant.now());
        when(repository.findByIdAndUserId(entry.getId(), userId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.submitFollowUp(userId, entry.getId(), "текст", null))
                .isInstanceOf(InvalidEntryStateException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void submitFollowUpOnUnknownEntryThrowsNotFound() {
        UUID entryId = UUID.randomUUID();
        when(repository.findByIdAndUserId(entryId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitFollowUp(userId, entryId, "текст", null))
                .isInstanceOf(EntryNotFoundException.class);
    }

    @Test
    void listEntriesMapsPageResult() {
        DiaryEntry entry = new DiaryEntry(UUID.randomUUID(), userId, date, DiaryEntryStatus.COMPLETE,
                "транскрипт", java.time.Instant.now());
        entry.setAiSummary("Резюме.");
        org.springframework.data.domain.Page<DiaryEntry> page =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(entry));
        when(repository.findByUserIdOrderByDateDesc(eq(userId), any())).thenReturn(page);

        PageResponse<EntrySummaryResponse> result = service.listEntries(userId, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().summary()).isEqualTo("Резюме.");
    }
}
