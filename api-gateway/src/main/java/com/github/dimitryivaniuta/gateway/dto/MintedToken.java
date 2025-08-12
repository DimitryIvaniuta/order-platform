package com.github.dimitryivaniuta.gateway.dto;

import java.time.Duration;

/**
 * Value object for minted token details.
 */
public record MintedToken(String accessToken, Duration expiresIn) { }