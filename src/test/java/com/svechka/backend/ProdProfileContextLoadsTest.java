package com.svechka.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Confirms the "prod" profile wires up cleanly — the Yandex clients are constructed (RestClient
 * beans, prompt resources, ffmpeg-backed transcoder) without needing real credentials, since no
 * network call happens until a request actually comes in. Guards against bean-wiring regressions
 * (e.g. a missing @Bean or an ambiguous TranscriptionClient/DialogEngineClient) going unnoticed
 * because the default test run only ever exercises the "dev" profile.
 */
@SpringBootTest
@ActiveProfiles("prod")
@Testcontainers
class ProdProfileContextLoadsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void yandexProperties(DynamicPropertyRegistry registry) {
        registry.add("yandex.folder-id", () -> "test-folder-id");
        registry.add("yandex.api-key", () -> "test-api-key");
    }

    @Test
    void contextLoads() {
    }
}
