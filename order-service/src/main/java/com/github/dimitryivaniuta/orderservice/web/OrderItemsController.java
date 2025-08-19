package com.github.dimitryivaniuta.orderservice.web;

import com.github.dimitryivaniuta.orderservice.service.OrderItemService;
import com.github.dimitryivaniuta.orderservice.service.SecurityTenantResolver;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderItemRequest;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderItemResponse;
import com.github.dimitryivaniuta.orderservice.web.dto.OutboxRow;
import com.github.dimitryivaniuta.orderservice.web.dto.UpdateOrderItemRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/orders/{orderId}/items")
public class OrderItemsController {

    private final OrderItemService items;
    private final SecurityTenantResolver security;

    @GetMapping
    public Flux<OrderItemResponse> list(@PathVariable Long orderId) {
        return security.current().flatMapMany(ctx -> items.list(ctx.tenantId(), orderId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrderItemResponse> add(@PathVariable Long orderId, @Valid @RequestBody OrderItemRequest req) {
        return security.current().flatMap(ctx ->
                items.addItem(ctx.tenantId(), orderId, req, ctx.userId(), ctx.correlationId())
        );
    }

    @PutMapping("/{itemId}")
    public Mono<OrderItemResponse> update(@PathVariable Long orderId,
                                          @PathVariable Long itemId,
                                          @Valid @RequestBody UpdateOrderItemRequest req) {
        return security.current().flatMap(ctx ->
                items.updateItem(ctx.tenantId(), orderId, itemId, req, ctx.userId(), ctx.correlationId())
        );
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<OutboxRow> delete(@PathVariable Long orderId, @PathVariable Long itemId) {
        return security.current().flatMap(ctx ->
                items.removeItem(ctx.tenantId(), orderId, itemId, ctx.userId(), ctx.correlationId())
        );
    }
}
