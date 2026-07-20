package com.svechka.backend.ai.yandex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Requires a real {@code ffmpeg} binary on PATH, which isn't available in every environment
 * (notably not in this sandbox — no passwordless sudo to install it). Self-skips via {@link
 * Assumptions} when ffmpeg is missing rather than failing the build; it runs for real in the
 * backend's Docker image and in any CI environment that has ffmpeg installed.
 */
class FfmpegAudioTranscoderTest {

    private final FfmpegAudioTranscoder transcoder = new FfmpegAudioTranscoder();
    private Path wavFile;

    @BeforeEach
    void checkFfmpegAvailable() {
        Assumptions.assumeTrue(ffmpegOnPath(), "ffmpeg is not installed in this environment — skipping");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (wavFile != null) {
            Files.deleteIfExists(wavFile);
        }
    }

    @Test
    void transcodesWavToPcm16Mono48k() throws IOException {
        wavFile = writeSilentWav(1.0, 8_000);

        AudioTranscoder.TranscodedAudio result = transcoder.transcodeToPcm16Mono48k(wavFile);

        assertThat(result.sampleRateHertz()).isEqualTo(48_000);
        assertThat(result.pcmBytes()).isNotEmpty();
        assertThat(result.durationSeconds()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.2));
    }

    @Test
    void throwsAudioTranscodingExceptionForUnreadableInput() throws IOException {
        wavFile = Files.createTempFile("svechka-not-audio", ".webm");
        Files.write(wavFile, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});

        assertThatThrownBy(() -> transcoder.transcodeToPcm16Mono48k(wavFile))
                .isInstanceOf(AudioTranscodingException.class);
    }

    private static boolean ffmpegOnPath() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while checking for ffmpeg");
            return false;
        }
    }

    /** Builds a minimal valid mono 16-bit PCM WAV file of silence, entirely in Java. */
    private static Path writeSilentWav(double seconds, int sampleRateHertz) throws IOException {
        int numSamples = (int) Math.round(seconds * sampleRateHertz);
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRateHertz * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = numSamples * blockAlign;

        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes());
        buffer.put("fmt ".getBytes());
        buffer.putInt(16);
        buffer.putShort((short) 1); // PCM
        buffer.putShort((short) numChannels);
        buffer.putInt(sampleRateHertz);
        buffer.putInt(byteRate);
        buffer.putShort((short) blockAlign);
        buffer.putShort((short) bitsPerSample);
        buffer.put("data".getBytes());
        buffer.putInt(dataSize);
        buffer.put(new byte[dataSize]); // silence

        Path file = Files.createTempFile("svechka-test-silence", ".wav");
        Files.write(file, buffer.array());
        return file;
    }
}
