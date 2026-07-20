package com.svechka.backend.ai.openai;

import com.svechka.backend.ai.AiProperties;
import com.svechka.backend.ai.AiServiceException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiTranscriptionClientTest {

    private MockWebServer server;
    private OpenAiTranscriptionClient client;
    private Path audioFile;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        AiProperties properties = new AiProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setApiKey("test-key");
        properties.setTranscriptionModel("whisper-1");
        properties.setTimeoutSeconds(5);

        RestClient restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
        client = new OpenAiTranscriptionClient(restClient, properties);

        audioFile = Files.createTempFile("svechka-test-audio", ".webm");
        Files.write(audioFile, new byte[] {1, 2, 3, 4});
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        Files.deleteIfExists(audioFile);
    }

    @Test
    void transcribeSendsMultipartRequestAndReturnsText() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\": \"расшифрованный текст дня\"}"));

        String text = client.transcribe(audioFile, "recording.webm");

        assertThat(text).isEqualTo("расшифрованный текст дня");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/audio/transcriptions");
        assertThat(request.getHeader("Content-Type")).contains("multipart/form-data");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("whisper-1");
        assertThat(body).contains("recording.webm");
    }

    @Test
    void networkFailureIsRetriedExactlyOnce() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\": \"ok after retry\"}"));

        String text = client.transcribe(audioFile, "recording.webm");

        assertThat(text).isEqualTo("ok after retry");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void clientErrorFromProviderIsNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(413).setBody("{\"error\":\"file too large\"}"));

        assertThatThrownBy(() -> client.transcribe(audioFile, "recording.webm"))
                .isInstanceOf(AiServiceException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
