package com.svechka.backend.ai.yandex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@Profile("prod")
public class FfmpegAudioTranscoder implements AudioTranscoder {

    private static final Logger log = LoggerFactory.getLogger(FfmpegAudioTranscoder.class);
    private static final int SAMPLE_RATE_HERTZ = 48_000;
    private static final long TIMEOUT_SECONDS = 25;

    @Override
    public TranscodedAudio transcodeToPcm16Mono48k(Path input) {
        long startedAt = System.currentTimeMillis();
        long inputSize;
        try {
            inputSize = java.nio.file.Files.size(input);
        } catch (IOException e) {
            inputSize = -1;
        }
        log.info("Starting ffmpeg transcode of {} ({} bytes)", input.getFileName(), inputSize);

        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", input.toAbsolutePath().toString(),
                "-vn",
                "-ac", "1",
                "-ar", String.valueOf(SAMPLE_RATE_HERTZ),
                "-f", "s16le",
                "-loglevel", "error",
                "-"
        );

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new AudioTranscodingException("Could not start ffmpeg — is it installed?", e);
        }

        // stdout carries the PCM payload, stderr carries diagnostics; both must be drained
        // concurrently or a large enough clip will deadlock on ffmpeg's output pipe.
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAllBytes(process));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readErrorOutput(process));

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AudioTranscodingException("Interrupted while waiting for ffmpeg", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new AudioTranscodingException("ffmpeg timed out after " + TIMEOUT_SECONDS + "s");
        }

        byte[] pcm = stdout.join();
        if (process.exitValue() != 0) {
            String errorOutput = stderr.join();
            log.warn("ffmpeg exited with code {}: {}", process.exitValue(), errorOutput);
            throw new AudioTranscodingException("ffmpeg failed to transcode the uploaded audio");
        }
        if (pcm.length == 0) {
            throw new AudioTranscodingException("ffmpeg produced no audio output");
        }

        log.info("Finished ffmpeg transcode: {} PCM bytes in {}ms", pcm.length,
                System.currentTimeMillis() - startedAt);
        return new TranscodedAudio(pcm, SAMPLE_RATE_HERTZ);
    }

    private static byte[] readAllBytes(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read ffmpeg stdout", e);
        }
    }

    private static String readErrorOutput(Process process) {
        try {
            return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(could not read ffmpeg stderr: " + e.getMessage() + ")";
        }
    }
}
