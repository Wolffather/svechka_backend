package com.svechka.backend.ai.yandex;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity-checks the fixture set that {@link YandexPromptFixtureLiveTest} runs against — runs on
 * every build (no credentials needed) so a broken/missing fixture is caught immediately instead
 * of silently shrinking the sample the next time someone runs the live evaluation by hand.
 */
class SampleTranscriptFixturesTest {

    private static final Set<String> EXPECTED_CATEGORIES =
            Set.of("short", "neutral", "detailed", "joyful", "heavy", "crisis");

    @Test
    void fixtureSetCoversAllRequiredCategoriesWithAtLeastFifteenTranscripts() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:sample-transcripts/**/*.txt");

        assertThat(resources.length)
                .as("TZ_ai_module.md section 6 requires 15-20 sample transcripts")
                .isBetween(15, 20);

        Set<String> categoriesSeen = Arrays.stream(resources)
                .map(this::categoryOf)
                .collect(Collectors.toSet());
        assertThat(categoriesSeen).containsExactlyInAnyOrderElementsOf(EXPECTED_CATEGORIES);

        for (Resource resource : resources) {
            String text = Files.readString(Path.of(resource.getURI()), StandardCharsets.UTF_8);
            assertThat(text).as("%s should not be blank", resource.getFilename()).isNotBlank();
        }
    }

    private String categoryOf(Resource resource) {
        try {
            return Path.of(resource.getURI()).getParent().getFileName().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
