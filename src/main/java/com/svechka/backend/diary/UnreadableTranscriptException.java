package com.svechka.backend.diary;

import com.svechka.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public class UnreadableTranscriptException extends ApiException {

    public UnreadableTranscriptException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "Не удалось распознать запись. Попробуйте перезаписать.");
    }
}
