package com.github.dimitryivaniuta.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Adyen response for POST /payments/{paymentPspReference}/refunds
 * Example:
 * {
 *   "pspReference":"8815590898585498",
 *   "paymentPspReference":"991559089833016J",
 *   "merchantAccount":"YourMerchant",
 *   "status":"received"
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResponse(
        @JsonProperty("pspReference") String pspReference,
        @JsonProperty("paymentPspReference") String paymentPspReference,
        @JsonProperty("merchantAccount") String merchantAccount,
        /** Usually "received". */
        @JsonProperty("status") String status
) {}
