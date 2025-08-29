package com.github.dimitryivaniuta.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

@Jacksonized
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentAuthResponse(
        @JsonProperty("resultCode")    String resultCode,
        @JsonProperty("pspReference")  String pspReference,
        @JsonProperty("refusalReason") String refusalReason,
        @JsonProperty("action")        Map<String, Object> action
) {}
