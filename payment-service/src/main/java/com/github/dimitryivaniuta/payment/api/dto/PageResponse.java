package com.github.dimitryivaniuta.payment.api.dto;

import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Simple generic page response for list endpoints. */
@Jacksonized
@Builder
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
