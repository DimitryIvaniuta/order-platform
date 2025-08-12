package com.github.dimitryivaniuta.gateway.dto;

/** Minimal user projection row for login verification. */
public record UserRow(Long id, String username, String passwordHash, short status) { }