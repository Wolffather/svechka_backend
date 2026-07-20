package com.svechka.backend.ai;

import org.slf4j.Logger;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Supplier;

/**
 * Shared call-with-retry plumbing for AI provider clients (OpenAI-compatible, Yandex Cloud,
 * or any future provider): exactly one retry on a network-level failure, never on an HTTP
 * error response from the provider (4xx or 5xx). Logs only the call-site name, duration and
 * status code at INFO — never request/response content (transcripts, prompts) at that level.
 *
 * <p>Deliberately holds no {@code RestClient} itself — some providers need just one (OpenAI),
 * others need several against different hosts (Yandex's STT/GPT/operation-polling endpoints
 * are all separate hosts) — so each subclass owns whichever client field(s) it needs.
 */
public abstract class AbstractRetryingAiClient {

    protected abstract Logger log();

    protected <T> T callWithRetry(String callSite, Supplier<T> action) {
        long start = System.currentTimeMillis();
        try {
            T result = attempt(callSite, action, true);
            log().info("AI call {} succeeded in {}ms", callSite, System.currentTimeMillis() - start);
            return result;
        } catch (HttpStatusCodeException e) {
            log().info("AI call {} failed with status {} in {}ms", callSite, e.getStatusCode().value(),
                    System.currentTimeMillis() - start);
            throw new AiServiceException(e);
        } catch (ResourceAccessException e) {
            log().info("AI call {} failed after retry in {}ms", callSite, System.currentTimeMillis() - start);
            throw new AiServiceException(e);
        }
    }

    private <T> T attempt(String callSite, Supplier<T> action, boolean retryOnNetworkError) {
        try {
            return action.get();
        } catch (ResourceAccessException e) {
            if (!retryOnNetworkError) {
                throw e;
            }
            log().warn("AI call {} hit a network error, retrying once", callSite);
            return attempt(callSite, action, false);
        }
    }
}
