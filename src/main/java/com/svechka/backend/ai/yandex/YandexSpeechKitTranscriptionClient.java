package com.svechka.backend.ai.yandex;

import com.svechka.backend.ai.AbstractRetryingAiClient;
import com.svechka.backend.ai.AiServiceException;
import com.svechka.backend.ai.TranscriptionClient;
import com.svechka.backend.ai.YandexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Talks to Yandex Cloud SpeechKit STT. Recordings are transcoded to raw PCM first (see
 * {@link AudioTranscoder}), then routed to whichever of SpeechKit's two REST APIs fits:
 *
 * <ul>
 *   <li><b>Synchronous</b> ({@code /speech/v1/stt:recognize}) — single round trip, but the
 *       provider limits it to short (&le;30s) single-channel clips.</li>
 *   <li><b>Long-running</b> ({@code /speech/stt/v2/longRunningRecognize}) — submits a job and
 *       polls {@code operation.api.cloud.yandex.net} until it completes; used for anything
 *       past the sync limit, which in practice is most real diary entries (recordings run up
 *       to ~10 minutes).</li>
 * </ul>
 *
 * The choice is entirely internal — {@link TranscriptionClient#transcribe} looks identical
 * either way.
 */
@Component
@Profile("prod")
public class YandexSpeechKitTranscriptionClient extends AbstractRetryingAiClient implements TranscriptionClient {

    private static final Logger log = LoggerFactory.getLogger(YandexSpeechKitTranscriptionClient.class);

    // SpeechKit documents the sync endpoint as good for up to 30s of single-channel audio;
    // stay under that with a safety margin rather than hugging the exact limit.
    private static final double SYNC_MAX_SECONDS = 25;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    // Yandex docs cite roughly 10s of processing per minute of audio; our recordings cap at
    // 10 minutes, so this comfortably covers the slowest realistic case.
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(180);

    private final RestClient yandexSttRestClient;
    private final RestClient yandexTranscribeRestClient;
    private final RestClient yandexOperationRestClient;
    private final YandexProperties yandexProperties;
    private final AudioTranscoder audioTranscoder;

    public YandexSpeechKitTranscriptionClient(RestClient yandexSttRestClient,
                                               RestClient yandexTranscribeRestClient,
                                               RestClient yandexOperationRestClient,
                                               YandexProperties yandexProperties,
                                               AudioTranscoder audioTranscoder) {
        this.yandexSttRestClient = yandexSttRestClient;
        this.yandexTranscribeRestClient = yandexTranscribeRestClient;
        this.yandexOperationRestClient = yandexOperationRestClient;
        this.yandexProperties = yandexProperties;
        this.audioTranscoder = audioTranscoder;
    }

    @Override
    protected Logger log() {
        return log;
    }

    @Override
    public String transcribe(Path audioFile, String originalFilename) {
        AudioTranscoder.TranscodedAudio audio;
        try {
            audio = audioTranscoder.transcodeToPcm16Mono48k(audioFile);
        } catch (AudioTranscodingException e) {
            log.warn("Audio transcoding failed, treating the recording as unreadable: {}", e.getMessage());
            return "";
        }

        if (audio.durationSeconds() <= SYNC_MAX_SECONDS) {
            return recognizeSync(audio.pcmBytes(), audio.sampleRateHertz());
        }
        return recognizeAsync(audio.pcmBytes(), audio.sampleRateHertz());
    }

    private String recognizeSync(byte[] pcm, int sampleRateHertz) {
        SyncRecognitionResponse response = callWithRetry("stt-sync", () -> yandexSttRestClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/speech/v1/stt:recognize")
                        .queryParam("folderId", yandexProperties.getFolderId())
                        .queryParam("lang", "ru-RU")
                        .queryParam("format", "lpcm")
                        .queryParam("sampleRateHertz", sampleRateHertz)
                        .build())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(pcm)
                .retrieve()
                .body(SyncRecognitionResponse.class));

        return response != null && response.result() != null ? response.result() : "";
    }

    private String recognizeAsync(byte[] pcm, int sampleRateHertz) {
        String base64Audio = Base64.getEncoder().encodeToString(pcm);
        LongRunningRecognizeRequest request = new LongRunningRecognizeRequest(
                new RecognitionConfig(
                        // sampleRateHertz/audioChannelCount are proto int64 fields — canonical
                        // proto3 JSON encodes 64-bit integers as strings, not bare numbers.
                        new RecognitionSpec("LINEAR16_PCM", String.valueOf(sampleRateHertz), "ru-RU",
                                yandexProperties.getSttModel(), "1"),
                        yandexProperties.getFolderId()),
                new RecognitionAudio(base64Audio));

        OperationEnvelope submitted = callWithRetry("stt-async-submit", () -> yandexTranscribeRestClient.post()
                .uri("/speech/stt/v2/longRunningRecognize")
                .body(request)
                .retrieve()
                .body(OperationEnvelope.class));

        if (submitted == null || submitted.id() == null) {
            throw new AiServiceException(new IllegalStateException("Yandex STT did not return an operation id"));
        }

        OperationEnvelope finished = pollOperation(submitted.id());
        if (finished.error() != null) {
            throw new AiServiceException(new IllegalStateException("Yandex STT operation failed: " + finished.error()));
        }
        return extractText(finished.response());
    }

    private OperationEnvelope pollOperation(String operationId) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            OperationEnvelope operation = callWithRetry("stt-async-poll", () -> yandexOperationRestClient.get()
                    .uri("/operations/{id}", operationId)
                    .retrieve()
                    .body(OperationEnvelope.class));

            if (operation != null && operation.done()) {
                return operation;
            }
            sleep(POLL_INTERVAL);
        }
        throw new AiServiceException(
                new IllegalStateException("Yandex STT operation " + operationId + " did not finish in time"));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> response) {
        if (response == null) {
            return "";
        }
        Object chunksObj = response.get("chunks");
        if (!(chunksObj instanceof List<?> chunks)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object chunkObj : chunks) {
            if (!(chunkObj instanceof Map<?, ?> chunk)) {
                continue;
            }
            Object alternativesObj = ((Map<String, Object>) chunk).get("alternatives");
            if (alternativesObj instanceof List<?> alternatives && !alternatives.isEmpty()
                    && alternatives.get(0) instanceof Map<?, ?> firstAlternative) {
                Object alternativeText = ((Map<String, Object>) firstAlternative).get("text");
                if (alternativeText != null) {
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                    text.append(alternativeText);
                }
            }
        }
        return text.toString();
    }

    private record SyncRecognitionResponse(String result) {
    }

    private record LongRunningRecognizeRequest(RecognitionConfig config, RecognitionAudio audio) {
    }

    private record RecognitionConfig(RecognitionSpec specification, String folderId) {
    }

    private record RecognitionSpec(String audioEncoding, String sampleRateHertz, String languageCode, String model,
                                    String audioChannelCount) {
    }

    private record RecognitionAudio(String content) {
    }

    private record OperationEnvelope(String id, boolean done, Map<String, Object> response,
                                      Map<String, Object> error) {
    }
}
