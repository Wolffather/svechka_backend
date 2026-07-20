package com.svechka.backend.diary;

import com.svechka.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public class EntryNotFoundException extends ApiException {

    public EntryNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Diary entry not found");
    }
}
