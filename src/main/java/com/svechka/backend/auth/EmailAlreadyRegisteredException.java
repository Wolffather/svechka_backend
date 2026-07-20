package com.svechka.backend.auth;

import com.svechka.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends ApiException {

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "This email is already registered");
    }
}
