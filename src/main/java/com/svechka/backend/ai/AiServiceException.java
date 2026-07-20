package com.svechka.backend.ai;

import com.svechka.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public class AiServiceException extends ApiException {

    public AiServiceException(Throwable cause) {
        super(HttpStatus.GATEWAY_TIMEOUT, "AI service is currently unavailable, please try again");
        initCause(cause);
    }
}
