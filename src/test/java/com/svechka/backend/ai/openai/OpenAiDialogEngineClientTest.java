package com.svechka.backend.ai.openai;

import com.svechka.backend.ai.AiProperties;
import com.svechka.backend.ai.AiServiceException;
import com.svechka.backend.ai.FollowUpDecision;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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

class OpenAiDialogEngineClientTest {

    private MockWebServer server;
    private OpenAiDialogEngineClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        AiProperties properties = new AiProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setApiKey("test-key");
        properties.setChatModel("gpt-4o-mini");
        properties.setTimeoutSeconds(5);

        RestClient restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
        client = new OpenAiDialogEngineClient(restClient, properties, new JsonMapper(),
                new PathMatchingResourcePatternResolver());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void followUpQuestionIsParsedWhenPresent() {
        enqueueChatCompletion("{\"question\": \"Что случилось потом?\", \"crisisFlag\": false}");

        FollowUpDecision decision = client.decideFollowUp("Сегодня был странный день");

        assertThat(decision.question()).isEqualTo("Что случилось потом?");
        assertThat(decision.crisisDetected()).isFalse();
    }

    @Test
    void followUpQuestionIsNullWhenModelReturnsNull() {
        enqueueChatCompletion("{\"question\": null, \"crisisFlag\": false}");

        FollowUpDecision decision = client.decideFollowUp("Подробный рассказ о дне");

        assertThat(decision.question()).isNull();
        assertThat(decision.crisisDetected()).isFalse();
    }

    @Test
    void followUpQuestionIsNullWhenModelReturnsMalformedJson() {
        enqueueChatCompletion("this is not json at all");

        FollowUpDecision decision = client.decideFollowUp("Рассказ о дне");

        assertThat(decision.question()).isNull();
        assertThat(decision.crisisDetected()).isFalse();
    }

    @Test
    void followUpJsonWrappedInMarkdownCodeFenceIsStillParsed() {
        // Confirmed live against the real YandexGPT API (which speaks a native, non-OpenAI
        // protocol but shares this prompt): it sometimes wraps strict-JSON responses in a code
        // fence even though the prompt asks for JSON only — some OpenAI-compatible providers do
        // the same thing.
        enqueueChatCompletion("```\n{\"question\": null, \"crisisFlag\": true, "
                + "\"crisisMessage\": \"Спасибо, что рассказал об этом.\"}\n```");

        FollowUpDecision decision = client.decideFollowUp("тяжёлый транскрипт");

        assertThat(decision.crisisDetected()).isTrue();
        assertThat(decision.crisisMessage()).isEqualTo("Спасибо, что рассказал об этом.");
    }

    @Test
    void crisisFlagIsParsedAndSuppressesQuestion() {
        enqueueChatCompletion("{\"question\": null, \"crisisFlag\": true, "
                + "\"crisisMessage\": \"Спасибо, что рассказал об этом.\"}");

        FollowUpDecision decision = client.decideFollowUp("тяжёлый транскрипт");

        assertThat(decision.crisisDetected()).isTrue();
        assertThat(decision.question()).isNull();
        assertThat(decision.crisisMessage()).isEqualTo("Спасибо, что рассказал об этом.");
    }

    @Test
    void crisisFlagFallsBackToDefaultMessageWhenModelOmitsIt() {
        enqueueChatCompletion("{\"question\": null, \"crisisFlag\": true}");

        FollowUpDecision decision = client.decideFollowUp("тяжёлый транскрипт");

        assertThat(decision.crisisDetected()).isTrue();
        assertThat(decision.crisisMessage()).isNotBlank();
    }

    @Test
    void summarizeDayReturnsModelContentVerbatim() {
        enqueueChatCompletion("Пользователь провёл спокойный день дома.");

        String summary = client.summarizeDay("Сегодня было тихо, я отдыхал дома.");

        assertThat(summary).isEqualTo("Пользователь провёл спокойный день дома.");
    }

    @Test
    void buildWeeklyInsightJoinsSummariesIntoOneRequest() {
        enqueueChatCompletion("За неделю пользователь несколько раз упоминал усталость.");

        String insight = client.buildWeeklyInsight(List.of("День 1: устал", "День 2: тоже устал"));

        assertThat(insight).isEqualTo("За неделю пользователь несколько раз упоминал усталость.");
    }

    @Test
    void buildWeeklyInsightStripsAccidentalBulletsAndNumbering() {
        enqueueChatCompletion("1. Первое наблюдение\n- Второе наблюдение\n\n* Третье наблюдение");

        String insight = client.buildWeeklyInsight(List.of("День 1", "День 2"));

        assertThat(insight).isEqualTo("Первое наблюдение\nВторое наблюдение\nТретье наблюдение");
    }

    @Test
    void networkFailureIsRetriedExactlyOnce() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        enqueueChatCompletion("Резюме после повтора.");

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

    private void enqueueChatCompletion(String messageContent) {
        String escaped = messageContent.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}]}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json));
    }
}
