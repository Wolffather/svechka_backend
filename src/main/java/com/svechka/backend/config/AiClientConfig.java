package com.svechka.backend.config;

import com.svechka.backend.ai.AiProperties;
import com.svechka.backend.ai.YandexProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AiClientConfig {

    private static final String YANDEX_STT_SYNC_URL = "https://stt.api.cloud.yandex.net";
    private static final String YANDEX_STT_ASYNC_URL = "https://transcribe.api.cloud.yandex.net";
    private static final String YANDEX_OPERATION_URL = "https://operation.api.cloud.yandex.net";
    private static final String YANDEX_GPT_URL = "https://llm.api.cloud.yandex.net";

    @Bean
    public RestClient aiRestClient(AiProperties aiProperties) {
        return buildClient(aiProperties.getBaseUrl(), aiProperties.getTimeoutSeconds(),
                "Bearer " + aiProperties.getApiKey());
    }

    @Bean
    public RestClient yandexSttRestClient(YandexProperties yandexProperties) {
        return buildClient(YANDEX_STT_SYNC_URL, yandexProperties.getTimeoutSeconds(), yandexAuth(yandexProperties));
    }

    @Bean
    public RestClient yandexTranscribeRestClient(YandexProperties yandexProperties) {
        return buildClient(YANDEX_STT_ASYNC_URL, yandexProperties.getTimeoutSeconds(), yandexAuth(yandexProperties));
    }

    @Bean
    public RestClient yandexOperationRestClient(YandexProperties yandexProperties) {
        return buildClient(YANDEX_OPERATION_URL, yandexProperties.getTimeoutSeconds(), yandexAuth(yandexProperties));
    }

    @Bean
    public RestClient yandexGptRestClient(YandexProperties yandexProperties) {
        return buildClient(YANDEX_GPT_URL, yandexProperties.getTimeoutSeconds(), yandexAuth(yandexProperties));
    }

    private static String yandexAuth(YandexProperties yandexProperties) {
        // Yandex Cloud static API keys authenticate with "Api-Key <key>", not "Bearer <key>"
        // (that scheme is reserved for short-lived IAM tokens) — confirmed against the
        // official yandex-ai-studio-sdk's own auth tests.
        return "Api-Key " + yandexProperties.getApiKey();
    }

    private static RestClient buildClient(String baseUrl, int timeoutSeconds, String authorizationHeader) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", authorizationHeader)
                .build();
    }
}
