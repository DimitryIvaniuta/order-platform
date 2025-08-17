package com.github.dimitryivaniuta.orderservice.web.dto;

import java.util.List;

public record ClaimedBatch(
        List<OutboxRow> rows,
        int attemptsAfterLease
) {
}
