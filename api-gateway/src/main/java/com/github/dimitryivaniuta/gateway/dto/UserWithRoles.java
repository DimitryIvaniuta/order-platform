package com.github.dimitryivaniuta.gateway.dto;

import com.github.dimitryivaniuta.gateway.model.UserEntity;

import java.util.List;

/**
 * Internal aggregation of user and roles.
 */
public record UserWithRoles(UserEntity user, List<String> roles) { }