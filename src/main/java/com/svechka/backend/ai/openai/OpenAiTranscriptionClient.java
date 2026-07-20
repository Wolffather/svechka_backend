package com.svechka.backend.ai.openai;

import com.svechka.backend.ai.AbstractRetryingAiClient;
import com.svechka.backend.ai.AiProperties;
import com.svechka.backend.ai.TranscriptionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

/**
 * Talks to a Whisper-compatible transcription endpoint (OpenAI's /audio/transcriptions,
 * or any RU aggregator that mirrors the same contract). Swap providers via ai.base-url /
 * ai.api-key / ai.transcription-model config only.
 *
 * <p>Active on the {@code dev} profile — the {@code prod} profile uses
 * {@link com.svechka.backend.ai.yandex.YandexSpeechKitTranscriptionClient} instead.
 */
@Component
@Profile("dev")
public class OpenAiTranscriptionClient extends AbstractRetryingAiClient implements TranscriptionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTranscriptionClient.class);

    private final RestClient restClient;
    private final AiProperties aiProperties;

    public OpenAiTranscriptionClient(RestClient aiRestClient, AiProperties aiProperties) {
        this.restClient = aiRestClient;
        this.aiProperties = aiProperties;
    }

    @Override
    protected Logger log() {
        return log;
    }

    @Override
    public String transcribe(Path audioFile, String originalFilename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audioFile) {
            @Override
            public String getFilename() {
                return originalFilename != null ? originalFilename : "audio.webm";
            }
        });
        body.add("model", aiProperties.getTranscriptionModel());

        TranscriptionResponse response = callWithRetry("transcription", () -> restClient.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(TranscriptionResponse.class));

        return response != null ? response.text() : "";
    }

    private record TranscriptionResponse(String text) {
    }
}
