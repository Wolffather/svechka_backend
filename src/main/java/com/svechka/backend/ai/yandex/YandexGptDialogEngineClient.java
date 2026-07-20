package com.svechka.backend.ai.yandex;

import com.svechka.backend.ai.AbstractRetryingAiClient;
import com.svechka.backend.ai.DialogEngineClient;
import com.svechka.backend.ai.FollowUpDecision;
import com.svechka.backend.ai.YandexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Talks to YandexGPT via the native {@code foundationModels/v1/completion} endpoint (not the
 * OpenAI-compatibility shim — the native API gives explicit control over temperature/maxTokens
 * and matches the officially published request/response contract 1:1). Reuses the exact same
 * system prompt resource files as the dev-profile OpenAI client — the prompts are provider-
 * agnostic; only the transport differs.
 */
@Component
@Profile("prod")
public class YandexGptDialogEngineClient extends AbstractRetryingAiClient implements DialogEngineClient {

    private static final Logger log = LoggerFactory.getLogger(YandexGptDialogEngineClient.class);

    /** Used only if the model flags a crisis but leaves crisisMessage empty — should be rare. */
    private static final String DEFAULT_CRISIS_MESSAGE =
            "Спасибо, что рассказал(а) об этом. То, что тебе сейчас тяжело — это важно, и ты не один(на) "
                    + "с этим. Если можешь, поговори с кем-то, кому доверяешь, или обратись за поддержкой "
                    + "к специалистам — психологу или службе психологической помощи в своей стране.";

    private static final double TEMPERATURE = 0.4;
    private static final String MAX_TOKENS = "800"; // int64 field -> string per proto3 JSON mapping

    private final RestClient yandexGptRestClient;
    private final YandexProperties yandexProperties;
    private final ObjectMapper objectMapper;
    private final String followUpSystemPrompt;
    private final String summarySystemPrompt;
    private final String weeklyInsightSystemPrompt;

    public YandexGptDialogEngineClient(RestClient yandexGptRestClient, YandexProperties yandexProperties,
                                        ObjectMapper objectMapper, ResourcePatternResolver resourceResolver) {
        this.yandexGptRestClient = yandexGptRestClient;
        this.yandexProperties = yandexProperties;
        this.objectMapper = objectMapper;
        this.followUpSystemPrompt = readPrompt(resourceResolver, "classpath:prompts/follow-up-system-prompt.txt");
        this.summarySystemPrompt = readPrompt(resourceResolver, "classpath:prompts/summary-system-prompt.txt");
        this.weeklyInsightSystemPrompt =
                readPrompt(resourceResolver, "classpath:prompts/weekly-insight-system-prompt.txt");
    }

    @Override
    protected Logger log() {
        return log;
    }

    @Override
    public FollowUpDecision decideFollowUp(String transcript) {
        String content = complete("follow-up", followUpSystemPrompt, transcript);
        try {
            FollowUpJson parsed = objectMapper.readValue(stripMarkdownFence(content), FollowUpJson.class);
            boolean crisis = Boolean.TRUE.equals(parsed.crisisFlag());
            if (crisis) {
                String message = parsed.crisisMessage() != null && !parsed.crisisMessage().isBlank()
                        ? parsed.crisisMessage()
                        : DEFAULT_CRISIS_MESSAGE;
                return new FollowUpDecision(null, true, message);
            }
            String question = parsed.question() != null && !parsed.question().isBlank() ? parsed.question() : null;
            return new FollowUpDecision(question, false, null);
        } catch (JacksonException e) {
            // Never let a malformed model response fail the user's request — fall back to
            // "no question" and log the incident so the prompt can be reviewed.
            log.warn("Could not parse follow-up JSON from YandexGPT, treating as 'no question'");
            return FollowUpDecision.none();
        }
    }

    @Override
    public String summarizeDay(String text) {
        return complete("summary", summarySystemPrompt, text);
    }

    @Override
    public String buildWeeklyInsight(List<String> dailySummaries) {
        String joined = String.join("\n", dailySummaries);
        String raw = complete("weekly-insight", weeklyInsightSystemPrompt, joined);
        return cleanObservationLines(raw);
    }

    /**
     * YandexGPT sometimes wraps strict-JSON responses in a markdown code fence (```json ... ```
     * or ``` ... ```) even though the prompt asks for JSON only with nothing around it —
     * confirmed live against the real API. Strip it before parsing so that stylistic quirk
     * doesn't fall back to "no question", which would silently skip the crisis branch.
     */
    private static String stripMarkdownFence(String content) {
        String trimmed = content.strip();
        if (!trimmed.startsWith("```")) {
            return content;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline == -1) {
            return content;
        }
        String withoutOpeningFence = trimmed.substring(firstNewline + 1);
        int closingFenceIndex = withoutOpeningFence.lastIndexOf("```");
        if (closingFenceIndex == -1) {
            return content;
        }
        return withoutOpeningFence.substring(0, closingFenceIndex).strip();
    }

    /** Strips accidental numbering/bullets so the frontend can reliably split on newlines. */
    private static String cleanObservationLines(String raw) {
        return raw.lines()
                .map(line -> line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "").trim())
                .filter(line -> !line.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private String complete(String callSite, String systemPrompt, String userContent) {
        String modelUri = "gpt://" + yandexProperties.getFolderId() + "/" + yandexProperties.getGptModel()
                + "/latest";
        CompletionRequest request = new CompletionRequest(
                modelUri,
                new CompletionOptions(false, TEMPERATURE, MAX_TOKENS),
                List.of(new Message("system", systemPrompt), new Message("user", userContent)));

        String rawBody = callWithRetry(callSite, () -> yandexGptRestClient.post()
                .uri("/foundationModels/v1/completion")
                .header("x-folder-id", yandexProperties.getFolderId())
                .body(request)
                .retrieve()
                .body(String.class));

        return extractText(rawBody);
    }

    /**
     * The completion RPC is nominally server-streaming; with {@code stream=false} the gateway
     * should send exactly one JSON object, but this defensively takes the last well-formed
     * line in case the provider ever emits incremental chunks anyway.
     */
    private String extractText(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        CompletionEnvelope lastParsed = null;
        for (String line : rawBody.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                lastParsed = objectMapper.readValue(line, CompletionEnvelope.class);
            } catch (JacksonException e) {
                log.warn("Could not parse a line of YandexGPT's completion response, skipping it");
            }
        }
        if (lastParsed == null || lastParsed.result() == null || lastParsed.result().alternatives() == null
                || lastParsed.result().alternatives().isEmpty()) {
            return "";
        }
        Message message = lastParsed.result().alternatives().getFirst().message();
        return message != null && message.text() != null ? message.text() : "";
    }

    private static String readPrompt(ResourcePatternResolver resolver, String location) {
        Resource resource = resolver.getResource(location);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read prompt resource: " + location, e);
        }
    }

    private record Message(String role, String text) {
    }

    private record CompletionOptions(boolean stream, double temperature, String maxTokens) {
    }

    private record CompletionRequest(String modelUri, CompletionOptions completionOptions, List<Message> messages) {
    }

    private record Alternative(Message message) {
    }

    private record CompletionResult(List<Alternative> alternatives) {
    }

    private record CompletionEnvelope(CompletionResult result) {
    }

    private record FollowUpJson(String question, Boolean crisisFlag, String crisisMessage) {
    }
}
