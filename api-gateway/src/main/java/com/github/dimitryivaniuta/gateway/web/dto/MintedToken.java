package com.github.dimitryivaniuta.gateway.web.dto;

import java.time.Duration;

/**
 * Value object for minted token details.
 */
public record MintedToken(String accessToken, Duration expiresIn) { }