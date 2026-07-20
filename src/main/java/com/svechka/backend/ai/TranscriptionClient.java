package com.svechka.backend.ai;

import java.nio.file.Path;

/**
 * Single entry point for turning a recorded audio file into text.
 * The rest of the application only depends on this interface, never on a specific provider.
 */
public interface TranscriptionClient {

    String transcribe(Path audioFile, String originalFilename);
}
