package com.svechka.backend.diary;

import com.svechka.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public class DuplicateEntryException extends ApiException {

    public DuplicateEntryException() {
        super(HttpStatus.CONFLICT, "You already have an entry for this date");
    }
}
