package com.svechka.backend.ai.yandex;

import java.nio.file.Path;

/**
 * Yandex SpeechKit only accepts OGG_OPUS, LINEAR16_PCM or MP3 — none of which match what a
 * browser's MediaRecorder actually produces (audio/webm;codecs=opus in Chrome/Firefox,
 * audio/mp4 in Safari). This normalizes any browser-recorded clip into raw 16-bit PCM before
 * it's sent to SpeechKit, so the STT clients never have to reason about container formats.
 */
public interface AudioTranscoder {

    TranscodedAudio transcodeToPcm16Mono48k(Path input);

    record TranscodedAudio(byte[] pcmBytes, int sampleRateHertz) {

        private static final int BYTES_PER_SAMPLE = 2; // 16-bit
        private static final int CHANNELS = 1; // mono

        public double durationSeconds() {
            return pcmBytes.length / (double) (sampleRateHertz * BYTES_PER_SAMPLE * CHANNELS);
        }
    }
}
