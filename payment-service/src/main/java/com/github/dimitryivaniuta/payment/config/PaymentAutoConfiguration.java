package com.github.dimitryivaniuta.payment.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties({
        FakePaymentProviderProperties.class
})
public class PaymentAutoConfiguration {
    //
}
