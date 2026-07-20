package com.svechka.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpdateEmailRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String newEmail,

        @NotBlank(message = "Current password is required")
        String currentPassword
) {
}
