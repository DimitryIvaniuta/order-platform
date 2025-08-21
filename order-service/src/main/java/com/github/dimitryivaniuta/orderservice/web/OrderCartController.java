package com.github.dimitryivaniuta.orderservice.web;

import com.github.dimitryivaniuta.orderservice.service.CartService;
import com.github.dimitryivaniuta.orderservice.web.dto.ApplyDiscountRequest;
import com.github.dimitryivaniuta.orderservice.web.dto.ChooseShippingRequest;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderResponse;
import com.github.dimitryivaniuta.orderservice.web.dto.UpdateCartItemRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/orders/cart")
@RequiredArgsConstructor
public class OrderCartController {
    private final CartService cart;

    @PostMapping("/items")
    public Mono<OrderResponse> addItem(@RequestBody AddCartItemRequest req) { return cart.addItem(req); }

    @PatchMapping("/items/{itemId}")
    public Mono<OrderResponse> updateItem(@PathVariable Long itemId, @RequestBody UpdateCartItemRequest req) { return cart.updateItem(itemId, req); }

    @DeleteMapping("/items/{itemId}")
    public Mono<OrderResponse> removeItem(@PathVariable Long itemId) { return cart.removeItem(itemId); }

    @PostMapping("/discount")
    public Mono<OrderResponse> applyDiscount(@RequestBody ApplyDiscountRequest req) { return cart.applyDiscount(req); }

    @PostMapping("/shipping")
    public Mono<OrderResponse> chooseShipping(@RequestBody ChooseShippingRequest req) { return cart.chooseShipping(req); }

    @PostMapping("/checkout")
    public Mono<OrderResponse> checkout() { return cart.checkout(); }

    @GetMapping
    public Mono<OrderResponse> currentCart() {
        return cart.getOrCreateCart().map(OrderResponse::fromEntity)
                .flatMap(resp -> cart.security.current().flatMap(ctx ->
                        cart.itemRepo.findByTenantIdAndOrderId(ctx.tenantId(), resp.id()).collectList().map(resp::withItems)
                ));
    }
}
