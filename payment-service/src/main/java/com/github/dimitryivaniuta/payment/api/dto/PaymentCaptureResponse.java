package com.github.dimitryivaniuta.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Adyen response for POST /payments/{paymentPspReference}/captures
 * Shape matches refunds: pspReference, paymentPspReference, merchantAccount, status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentCaptureResponse(
        @JsonProperty("pspReference") String pspReference,
        @JsonProperty("paymentPspReference") String paymentPspReference,
        @JsonProperty("merchantAccount") String merchantAccount,
        /** Usually "received". */
        @JsonProperty("status") String status
) {}
