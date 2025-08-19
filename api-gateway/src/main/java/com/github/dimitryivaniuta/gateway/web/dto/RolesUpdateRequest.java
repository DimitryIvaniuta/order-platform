package com.github.dimitryivaniuta.gateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public final class RolesUpdateRequest {
    private List<@NotBlank String> roles;
}