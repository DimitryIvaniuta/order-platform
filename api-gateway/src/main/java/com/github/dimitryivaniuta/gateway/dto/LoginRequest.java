package com.github.dimitryivaniuta.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Login request payload.
 */
@Data
public final class LoginRequest {

    /** Username or email (CI lookup). */
    @NotBlank
    private String usernameOrEmail;
    /** Password in clear; verified with BCrypt. */

    @NotBlank
    private String password;

    /** Target tenant id (required for mt claim). */
    @NotBlank
    private String tenantId;

}