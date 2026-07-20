package com.svechka.backend.diary;

import com.svechka.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidEntryStateException extends ApiException {

    public InvalidEntryStateException() {
        super(HttpStatus.CONFLICT, "This entry is not awaiting a follow-up answer");
    }
}
