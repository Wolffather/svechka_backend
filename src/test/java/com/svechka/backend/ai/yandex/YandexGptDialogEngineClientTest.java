package com.svechka.backend.ai.yandex;

import com.svechka.backend.ai.AiServiceException;
import com.svechka.backend.ai.FollowUpDecision;
import com.svechka.backend.ai.YandexProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YandexGptDialogEngineClientTest {

    private MockWebServer server;
    private YandexGptDialogEngineClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        YandexProperties properties = new YandexProperties();
        properties.setFolderId("test-folder");
        properties.setApiKey("test-key");
        properties.setGptModel("yandexgpt");
        properties.setTimeoutSeconds(5);

        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .defaultHeader("Authorization", "Api-Key test-key")
                .build();
        client = new YandexGptDialogEngineClient(restClient, properties, new JsonMapper(),
                new PathMatchingResourcePatternResolver());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void followUpQuestionIsParsedWhenPresent() {
        enqueueCompletion("{\"question\": \"Что случилось потом?\", \"crisisFlag\": false}");

        FollowUpDecision decision = client.decideFollowUp("Сегодня был странный день");

        assertThat(decision.question()).isEqualTo("Что случилось потом?");
        assertThat(decision.crisisDetected()).isFalse();
    }

    @Test
    void followUpQuestionIsNullWhenModelReturnsNull() {
        enqueueCompletion("{\"question\": null, \"crisisFlag\": false}");

        FollowUpDecision decision = client.decideFollowUp("Подробный рассказ о дне");

        assertThat(decision.question()).isNull();
        assertThat(decision.crisisDetected()).isFalse();
    }

    @Test
    void followUpQuestionIsNullWhenModelReturnsMalformedJson() {
        enqueueCompletion("this is not json at all");

        FollowUpDecision decision = client.decideFollowUp("Рассказ о дне");

        assertThat(decision.question()).isNull();
        assertThat(decision.crisisDetected()).isFalse();
    }

    @Test
    void followUpJsonWrappedInMarkdownCodeFenceIsStillParsed() {
        // Confirmed live against the real YandexGPT API: it sometimes wraps strict-JSON
        // responses in a code fence even though the prompt asks for JSON only.
        enqueueCompletion("```\n{\"question\": null, \"crisisFlag\": true, "
                + "\"crisisMessage\": \"Спасибо, что рассказал об этом.\"}\n```");

        FollowUpDecision decision = client.decideFollowUp("тяжёлый транскрипт");

        assertThat(decision.crisisDetected()).isTrue();
        assertThat(decision.crisisMessage()).isEqualTo("Спасибо, что рассказал об этом.");
    }

    @Test
    void crisisFlagIsParsedAndSuppressesQuestion() {
        enqueueCompletion("{\"question\": null, \"crisisFlag\": true, "
                + "\"crisisMessage\": \"Спасибо, что рассказал об этом.\"}");

        FollowUpDecision decision = client.decideFollowUp("тяжёлый транскрипт");

        assertThat(decision.crisisDetected()).isTrue();
        assertThat(decision.question()).isNull();
        assertThat(decision.crisisMessage()).isEqualTo("Спасибо, что рассказал об этом.");
    }

    @Test
    void crisisFlagFallsBackToDefaultMessageWhenModelOmitsIt() {
        enqueueCompletion("{\"question\": null, \"crisisFlag\": true}");

        FollowUpDecision decision = client.decideFollowUp("тяжёлый транскрипт");

        assertThat(decision.crisisDetected()).isTrue();
        assertThat(decision.crisisMessage()).isNotBlank();
    }

    @Test
    void summarizeDayReturnsModelContentVerbatim() {
        enqueueCompletion("Пользователь провёл спокойный день дома.");

        String summary = client.summarizeDay("Сегодня было тихо, я отдыхал дома.");

        assertThat(summary).isEqualTo("Пользователь провёл спокойный день дома.");
    }

    @Test
    void buildWeeklyInsightJoinsSummariesIntoOneRequest() {
        enqueueCompletion("За неделю пользователь несколько раз упоминал усталость.");

        String insight = client.buildWeeklyInsight(List.of("День 1: устал", "День 2: тоже устал"));

        assertThat(insight).isEqualTo("За неделю пользователь несколько раз упоминал усталость.");
    }

    @Test
    void buildWeeklyInsightStripsAccidentalBulletsAndNumbering() {
        enqueueCompletion("1. Первое наблюдение\n- Второе наблюдение\n\n* Третье наблюдение");

        String insight = client.buildWeeklyInsight(List.of("День 1", "День 2"));

        assertThat(insight).isEqualTo("Первое наблюдение\nВторое наблюдение\nТретье наблюдение");
    }

    @Test
    void requestUsesApiKeyAuthAndModelUriWithFolderId() throws InterruptedException {
        enqueueCompletion("резюме");

        client.summarizeDay("транскрипт");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/foundationModels/v1/completion");
        assertThat(request.getHeader("Authorization")).isEqualTo("Api-Key test-key");
        assertThat(request.getHeader("x-folder-id")).isEqualTo("test-folder");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("gpt://test-folder/yandexgpt/latest");
    }

    @Test
    void networkFailureIsRetriedExactlyOnce() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        enqueueCompletion("Резюме после повтора.");

        String summary = client.summarizeDay("транскрипт");

        assertThat(summary).isEqualTo("Резюме после повтора.");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void clientErrorFromProviderIsNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad request\"}"));

        assertThatThrownBy(() -> client.summarizeDay("транскрипт"))
                .isInstanceOf(AiServiceException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private void enqueueCompletion(String messageText) {
        String escaped = messageText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String json = "{\"result\":{\"alternatives\":[{\"message\":{\"role\":\"assistant\",\"text\":\""
                + escaped + "\"},\"status\":\"ALTERNATIVE_STATUS_FINAL\"}]}}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json));
    }
}
