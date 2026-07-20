package com.svechka.backend.ai.yandex;

import com.svechka.backend.ai.AiServiceException;
import com.svechka.backend.ai.YandexProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Exercises the HTTP/parsing logic in isolation from real audio transcoding — {@link
 * AudioTranscoder} is mocked so these tests don't depend on an ffmpeg binary being present
 * (see {@link FfmpegAudioTranscoder}, which is covered separately and skips itself when
 * ffmpeg isn't on PATH).
 */
@ExtendWith(MockitoExtension.class)
class YandexSpeechKitTranscriptionClientTest {

    @Mock
    private AudioTranscoder audioTranscoder;

    private MockWebServer server;
    private YandexSpeechKitTranscriptionClient client;
    private Path audioFile;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        YandexProperties properties = new YandexProperties();
        properties.setFolderId("test-folder");
        properties.setApiKey("test-key");
        properties.setSttModel("general");
        properties.setTimeoutSeconds(5);

        String baseUrl = server.url("/").toString();
        RestClient sttClient = restClient(baseUrl);
        RestClient transcribeClient = restClient(baseUrl);
        RestClient operationClient = restClient(baseUrl);

        client = new YandexSpeechKitTranscriptionClient(sttClient, transcribeClient, operationClient, properties,
                audioTranscoder);

        audioFile = Files.createTempFile("svechka-test-audio", ".webm");
        Files.write(audioFile, new byte[] {1, 2, 3, 4});
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        Files.deleteIfExists(audioFile);
    }

    @Test
    void shortAudioUsesSyncRecognition() throws InterruptedException {
        when(audioTranscoder.transcodeToPcm16Mono48k(audioFile))
                .thenReturn(shortPcm());
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\": \"расшифрованный текст дня\"}"));

        String text = client.transcribe(audioFile, "recording.webm");

        assertThat(text).isEqualTo("расшифрованный текст дня");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).startsWith("/speech/v1/stt:recognize");
        assertThat(request.getPath()).contains("folderId=test-folder");
        assertThat(request.getHeader("Authorization")).isEqualTo("Api-Key test-key");
    }

    @Test
    void longAudioUsesAsyncLongRunningRecognitionAndPolls() throws InterruptedException {
        when(audioTranscoder.transcodeToPcm16Mono48k(audioFile))
                .thenReturn(longPcm());
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"op-123\", \"done\": false}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"op-123\", \"done\": true, \"response\": "
                        + "{\"chunks\": [{\"alternatives\": [{\"text\": \"первая часть\"}]}, "
                        + "{\"alternatives\": [{\"text\": \"вторая часть\"}]}]}}"));

        String text = client.transcribe(audioFile, "recording.webm");

        assertThat(text).isEqualTo("первая часть вторая часть");

        RecordedRequest submitRequest = server.takeRequest();
        assertThat(submitRequest.getPath()).isEqualTo("/speech/stt/v2/longRunningRecognize");
        String submitBody = submitRequest.getBody().readUtf8();
        assertThat(submitBody).contains("\"sampleRateHertz\":\"48000\"");
        assertThat(submitBody).contains("test-folder");

        RecordedRequest pollRequest = server.takeRequest();
        assertThat(pollRequest.getPath()).isEqualTo("/operations/op-123");
    }

    @Test
    void asyncOperationErrorIsSurfacedAsAiServiceException() {
        when(audioTranscoder.transcodeToPcm16Mono48k(audioFile))
                .thenReturn(longPcm());
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"op-123\", \"done\": false}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"op-123\", \"done\": true, \"error\": {\"message\": \"boom\"}}"));

        assertThatThrownBy(() -> client.transcribe(audioFile, "recording.webm"))
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    void transcodingFailureYieldsBlankTranscriptInsteadOfThrowing() {
        when(audioTranscoder.transcodeToPcm16Mono48k(audioFile))
                .thenThrow(new AudioTranscodingException("ffmpeg exploded"));

        String text = client.transcribe(audioFile, "recording.webm");

        assertThat(text).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void networkFailureIsRetriedExactlyOnce() {
        when(audioTranscoder.transcodeToPcm16Mono48k(audioFile))
                .thenReturn(shortPcm());
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\": \"ok after retry\"}"));

        String text = client.transcribe(audioFile, "recording.webm");

        assertThat(text).isEqualTo("ok after retry");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void clientErrorFromProviderIsNotRetried() {
        when(audioTranscoder.transcodeToPcm16Mono48k(audioFile))
                .thenReturn(shortPcm());
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad request\"}"));

        assertThatThrownBy(() -> client.transcribe(audioFile, "recording.webm"))
                .isInstanceOf(AiServiceException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private static RestClient restClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Api-Key test-key")
                .build();
    }

    private static AudioTranscoder.TranscodedAudio shortPcm() {
        // 5 seconds at 48kHz/16-bit/mono, well under the 25s sync threshold.
        return new AudioTranscoder.TranscodedAudio(new byte[48_000 * 2 * 5], 48_000);
    }

    private static AudioTranscoder.TranscodedAudio longPcm() {
        // 40 seconds at 48kHz/16-bit/mono, over the 25s sync threshold.
        return new AudioTranscoder.TranscodedAudio(new byte[48_000 * 2 * 40], 48_000);
    }
}
