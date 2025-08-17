package com.github.dimitryivaniuta.orderservice.web.dto;

import com.github.dimitryivaniuta.orderservice.service.OrderService;
import com.github.dimitryivaniuta.orderservice.web.dto.CreateOrderRequest;
import com.github.dimitryivaniuta.orderservice.web.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orders")
public class OrderControllerApi {

    private final OrderService service;

    public OrderControllerApi(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
        return service.createOrder(req);
    }

    @GetMapping("/{id}")
    public Mono<OrderResponse> get(@PathVariable long id) {
        return service.getById(id);
    }
}
