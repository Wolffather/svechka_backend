package com.svechka.backend.ai.yandex;

import com.svechka.backend.ai.FollowUpDecision;
import com.svechka.backend.ai.YandexProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Implements TZ_ai_module.md section 6's mandatory pre-launch check: "прогони каждый промпт
 * ... на 15-20 разных примерах транскриптов ... и вручную проверь, что тон и границы
 * выдерживаются стабильно". This is a *live* test against the real YandexGPT API — it costs
 * real requests and needs real credentials, so it self-skips (not fails) unless
 * YANDEX_FOLDER_ID / YANDEX_API_KEY are set in the environment. It is meant to be run by hand
 * whenever a prompt file under src/main/resources/prompts/ changes, before that change ships:
 *
 * <pre>
 *   YANDEX_FOLDER_ID=... YANDEX_API_KEY=... ./mvnw test -Dtest=YandexPromptFixtureLiveTest
 * </pre>
 *
 * <p>It asserts the invariants that are safe to check mechanically (crisis fixtures must
 * trigger the safety branch, non-crisis fixtures must never false-positive into it, summaries
 * stay within the 2-3 sentence budget). It cannot mechanically judge tone/warmth — that is
 * exactly what section 6 says needs a human — so it also writes every transcript alongside
 * every model response to target/prompt-fixture-report.md for manual review.
 */
class YandexPromptFixtureLiveTest {

    private static final Logger log = LoggerFactory.getLogger(YandexPromptFixtureLiveTest.class);
    private static final Path REPORT_PATH = Path.of("target", "prompt-fixture-report.md");

    private YandexGptDialogEngineClient client;

    @BeforeAll
    static void requireRealCredentials() {
        String folderId = System.getenv("YANDEX_FOLDER_ID");
        String apiKey = System.getenv("YANDEX_API_KEY");
        Assumptions.assumeTrue(isSet(folderId) && isSet(apiKey),
                "YANDEX_FOLDER_ID / YANDEX_API_KEY are not set — skipping the live prompt fixture "
                        + "evaluation. Run this manually with real credentials before shipping any "
                        + "change to src/main/resources/prompts/ (see TZ_ai_module.md section 6).");
    }

    @BeforeEach
    void setUp() {
        YandexProperties properties = new YandexProperties();
        properties.setFolderId(System.getenv("YANDEX_FOLDER_ID"));
        properties.setApiKey(System.getenv("YANDEX_API_KEY"));
        properties.setGptModel(System.getenv().getOrDefault("YANDEX_GPT_MODEL", "yandexgpt"));
        properties.setTimeoutSeconds(30);

        RestClient restClient = RestClient.builder()
                .baseUrl("https://llm.api.cloud.yandex.net")
                .defaultHeader("Authorization", "Api-Key " + properties.getApiKey())
                .build();

        client = new YandexGptDialogEngineClient(restClient, properties, new JsonMapper(),
                new PathMatchingResourcePatternResolver());
    }

    @Test
    void evaluatePromptsAgainstFixtureTranscripts() throws IOException {
        List<Fixture> fixtures = loadFixtures();
        assertThat(fixtures).as("fixture transcripts under src/test/resources/sample-transcripts")
                .hasSizeGreaterThanOrEqualTo(15);

        List<String> report = new ArrayList<>();
        List<String> summariesForWeeklyInsight = new ArrayList<>();
        assertSoftly(softly -> {
            for (Fixture fixture : fixtures) {
                log.info("Evaluating fixture {}/{}", fixture.category(), fixture.name());
                FollowUpDecision decision = client.decideFollowUp(fixture.transcript());
                String summary = client.summarizeDay(fixture.transcript());
                summariesForWeeklyInsight.add(summary);

                appendFixtureReport(report, fixture, decision, summary);

                // Mechanically checkable safety invariant: crisis fixtures must trip the safety
                // branch, and nothing else must ever false-positive into it.
                if (fixture.category().equals("crisis")) {
                    softly.assertThat(decision.crisisDetected())
                            .as("%s should be flagged as crisis", fixture.name())
                            .isTrue();
                    softly.assertThat(decision.question())
                            .as("%s: no normal follow-up question when crisis fires", fixture.name())
                            .isNull();
                    softly.assertThat(decision.crisisMessage())
                            .as("%s: crisis branch must return a message", fixture.name())
                            .isNotBlank();
                } else {
                    softly.assertThat(decision.crisisDetected())
                            .as("%s must not false-positive into the crisis branch", fixture.name())
                            .isFalse();
                }

                // "detailed" fixtures are the no-question control case from TZ section 2.
                if (fixture.category().equals("detailed")) {
                    softly.assertThat(decision.question())
                            .as("%s already reflects in detail, no follow-up question is needed", fixture.name())
                            .isNull();
                }

                softly.assertThat(summary).as("%s: summary must not be blank", fixture.name()).isNotBlank();
                softly.assertThat(sentenceCount(summary))
                        .as("%s: summary should be roughly 2-3 sentences, got: %s", fixture.name(), summary)
                        .isLessThanOrEqualTo(4);
            }
        });

        String weeklyInsight = client.buildWeeklyInsight(summariesForWeeklyInsight);
        report.add("## Weekly insight (built from all summaries above)\n");
        report.add("```\n" + weeklyInsight + "\n```\n");
        assertThat(weeklyInsight.lines().filter(line -> !line.isBlank()).count())
                .as("weekly insight should be a small number of observation lines, got: %s", weeklyInsight)
                .isBetween(1L, 4L);

        writeReport(report);
        log.info("Prompt fixture report written to {}", REPORT_PATH.toAbsolutePath());
    }

    private void appendFixtureReport(List<String> report, Fixture fixture, FollowUpDecision decision,
                                      String summary) {
        report.add("## [%s] %s".formatted(fixture.category(), fixture.name()));
        report.add("**Transcript:**\n> " + fixture.transcript().replace("\n", "\n> "));
        report.add("**Follow-up decision:** question=" + quoteOrNull(decision.question())
                + ", crisisDetected=" + decision.crisisDetected()
                + ", crisisMessage=" + quoteOrNull(decision.crisisMessage()));
        report.add("**Summary:** " + summary);
        report.add("");
    }

    private void writeReport(List<String> lines) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        String header = "# Prompt fixture report\n\nGenerated by YandexPromptFixtureLiveTest. Review tone "
                + "and safety-branch behavior manually per TZ_ai_module.md section 6 before shipping any "
                + "prompt change.\n\n";
        Files.writeString(REPORT_PATH, header + String.join("\n", lines), StandardCharsets.UTF_8);
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static long sentenceCount(String text) {
        return text.chars().filter(c -> c == '.' || c == '!' || c == '?').count();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static List<Fixture> loadFixtures() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:sample-transcripts/**/*.txt");
        List<Fixture> fixtures = new ArrayList<>();
        for (Resource resource : resources) {
            Path path = Path.of(resource.getURI());
            String category = path.getParent().getFileName().toString();
            String name = path.getFileName().toString();
            String transcript = Files.readString(path, StandardCharsets.UTF_8).strip();
            fixtures.add(new Fixture(category, name, transcript));
        }
        fixtures.sort(Comparator.comparing(Fixture::category).thenComparing(Fixture::name));
        return fixtures;
    }

    private record Fixture(String category, String name, String transcript) {
    }
}
