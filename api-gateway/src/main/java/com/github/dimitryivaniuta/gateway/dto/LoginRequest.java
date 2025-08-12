package com.github.dimitryivaniuta.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Login request body. */
@Data
public final class LoginRequest {
    /** User's password (plaintext for verification only). */
    @NotBlank
    private String password;

    /** Username or login handle (case-insensitive). */
    @NotBlank
    private String username;
}
