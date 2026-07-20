package com.svechka.backend.ai.yandex;

/**
 * Signals that ffmpeg failed to decode/transcode the uploaded audio (missing binary, corrupt
 * input, unsupported codec, non-zero exit code). Callers should treat this the same as an
 * empty transcript — see {@code DiaryService.transcribeAndCleanUp}'s blank-transcript check.
 */
public class AudioTranscodingException extends RuntimeException {

    public AudioTranscodingException(String message) {
        super(message);
    }

    public AudioTranscodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
