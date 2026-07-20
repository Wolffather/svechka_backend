package com.svechka.backend.ai.openai;

import com.svechka.backend.ai.AbstractRetryingAiClient;
import com.svechka.backend.ai.AiProperties;
import com.svechka.backend.ai.DialogEngineClient;
import com.svechka.backend.ai.FollowUpDecision;
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
 * Talks to an OpenAI-compatible /chat/completions endpoint. Swap providers via ai.base-url /
 * ai.api-key / ai.chat-model config only. System prompts live under
 * src/main/resources/prompts/ so they can be tuned without touching this class.
 *
 * <p>Active on the {@code dev} profile — the {@code prod} profile uses
 * {@link com.svechka.backend.ai.yandex.YandexGptDialogEngineClient} instead.
 */
@Component
@Profile("dev")
public class OpenAiDialogEngineClient extends AbstractRetryingAiClient implements DialogEngineClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiDialogEngineClient.class);

    /** Used only if the model flags a crisis but leaves crisisMessage empty — should be rare. */
    private static final String DEFAULT_CRISIS_MESSAGE =
            "Спасибо, что рассказал(а) об этом. То, что тебе сейчас тяжело — это важно, и ты не один(на) "
                    + "с этим. Если можешь, поговори с кем-то, кому доверяешь, или обратись за поддержкой "
                    + "к специалистам — психологу или службе психологической помощи в своей стране.";

    private final RestClient restClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final String followUpSystemPrompt;
    private final String summarySystemPrompt;
    private final String weeklyInsightSystemPrompt;

    public OpenAiDialogEngineClient(RestClient aiRestClient, AiProperties aiProperties,
                                     ObjectMapper objectMapper, ResourcePatternResolver resourceResolver) {
        this.restClient = aiRestClient;
        this.aiProperties = aiProperties;
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
            log.warn("Could not parse follow-up JSON from the model, treating as 'no question'");
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
     * Some OpenAI-compatible providers wrap strict-JSON responses in a markdown code fence
     * (```json ... ``` or ``` ... ```) even though the prompt asks for JSON only with nothing
     * around it (confirmed live against YandexGPT's own native API — see the equivalent method
     * in YandexGptDialogEngineClient). Strip it before parsing so that stylistic quirk doesn't
     * fall back to "no question", which would silently skip the crisis branch.
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
        ChatCompletionRequest request = new ChatCompletionRequest(
                aiProperties.getChatModel(),
                List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", userContent)));

        ChatCompletionResponse response = callWithRetry(callSite, () -> restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(ChatCompletionResponse.class));

        if (response == null || response.choices().isEmpty()) {
            return "";
        }
        return response.choices().getFirst().message().content();
    }

    private static String readPrompt(ResourcePatternResolver resolver, String location) {
        Resource resource = resolver.getResource(location);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read prompt resource: " + location, e);
        }
    }

    private record ChatMessage(String role, String content) {
    }

    private record ChatCompletionRequest(String model, List<ChatMessage> messages) {
    }

    private record ChatCompletionResponse(List<Choice> choices) {
        private record Choice(ChatMessage message) {
        }
    }

    private record FollowUpJson(String question, Boolean crisisFlag, String crisisMessage) {
    }
}
