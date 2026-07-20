package com.svechka.backend.diary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes an uploaded audio blob to a short-lived temp file for the transcription call, and
 * guarantees its removal afterwards regardless of outcome (privacy requirement: audio never
 * outlives a single request).
 */
@Component
public class TempAudioFileService {

    private static final Logger log = LoggerFactory.getLogger(TempAudioFileService.class);

    public Path store(MultipartFile audio) {
        try {
            Path temp = Files.createTempFile("svechka-audio-", suffixFor(audio.getOriginalFilename()));
            audio.transferTo(temp);
            log.info("Stored uploaded audio: {} bytes, contentType={}, filename={}", audio.getSize(),
                    audio.getContentType(), audio.getOriginalFilename());
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store uploaded audio", e);
        }
    }

    public void delete(Path audioFile) {
        try {
            Files.deleteIfExists(audioFile);
        } catch (IOException e) {
            log.warn("Could not delete temp audio file {}", audioFile, e);
        }
    }

    private String suffixFor(String originalFilename) {
        if (originalFilename == null) {
            return ".audio";
        }
        int dot = originalFilename.lastIndexOf('.');
        return dot >= 0 ? originalFilename.substring(dot) : ".audio";
    }
}
