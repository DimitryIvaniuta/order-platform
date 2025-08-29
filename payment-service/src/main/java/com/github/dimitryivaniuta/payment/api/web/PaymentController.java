package com.github.dimitryivaniuta.payment.api.web;

import com.github.dimitryivaniuta.payment.api.dto.*;
import com.github.dimitryivaniuta.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive Payments API.
 *
 * Responsibilities:
 * - Authorize payment for an order (creates a payment intent).
 * - Capture previously authorized funds.
 * - Refund captured funds (full/partial).
 * - Query payments by id or order id.
 *
 * Multitenant: tenant/user/correlation are resolved from the JWT via SecurityTenantResolver.
 * All methods are non-blocking and return Mono/Flux.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Validated
public class PaymentController {

    private final PaymentService service;

    /** Create/authorize a payment for an order. */
    @PostMapping("/authorize")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PaymentView> authorize(@RequestBody PaymentAuthorizeRequest req) {
        return service.authorize(req);
    }

    /** Capture all/part of an authorized payment. */
    @PostMapping("/{paymentId}/capture")
    public Mono<CaptureView> capture(@PathVariable long paymentId,
                                     @RequestBody PaymentCaptureRequest req) {
        return service.capture(paymentId, req);
    }

    /** Refund all/part of a captured payment. */
    @PostMapping("/{paymentId}/refund")
    public Mono<RefundView> refund(@PathVariable long paymentId,
                                   @RequestBody RefundRequest req) {
        return service.refund(paymentId, req);
    }

    /** Read a payment by id. */
    @GetMapping("/{paymentId}")
    public Mono<PaymentView> getById(@PathVariable long paymentId) {
        return service.getById(paymentId);
    }

    /** List payments for a given order (uses tenant from JWT). */
    @GetMapping("/orders/{orderId}")
    public Flux<PaymentView> listByOrderId(@PathVariable long orderId) {
        return service.listByOrderId(orderId);
    }

    /** Test helper: simulate provider webhook success (e.g., after 3DS) */
    @PostMapping("/{paymentId}/simulate/webhook/success")
    public Mono<PaymentView> simulateWebhookSuccess(@PathVariable long paymentId) {
        return service.simulateWebhookSuccess(paymentId);
    }

    /** Test helper: simulate provider webhook failure */
    @PostMapping("/{paymentId}/simulate/webhook/failure")
    public Mono<PaymentView> simulateWebhookFailure(@PathVariable long paymentId) {
        return service.simulateWebhookFailure(paymentId);
    }
}