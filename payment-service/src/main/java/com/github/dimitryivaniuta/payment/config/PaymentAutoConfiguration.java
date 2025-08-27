package com.github.dimitryivaniuta.payment.config;

import com.github.dimitryivaniuta.payment.provider.adyen.AdyenClient;
import com.github.dimitryivaniuta.payment.provider.fake.FakePaymentProvider;
import com.github.dimitryivaniuta.payment.provider.stripe.StripeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        PaymentProviderProperties.class
})
public class PaymentAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "provider.fake.enabled", havingValue = "true", matchIfMissing = true)
    public FakePaymentProvider fakeProvider(PaymentProviderProperties providerProperties) {
        return new FakePaymentProvider(providerProperties);
    }

    @Bean
    @ConditionalOnProperty(name = "provider.adyen.enabled", havingValue = "true")
    public AdyenClient adyenClient(
            PaymentProviderProperties props
    ) {
        // You already have an AdyenClient; adapt its builder to use:
        // props.getAdyen().effectiveTimeouts(props.getCommon()), props.getAdyen().getBaseUrl(), etc.
        return new AdyenClient(props);
    }

    @Bean
    @ConditionalOnProperty(name = "provider.stripe.enabled", havingValue = "true")
    public StripeClient stripeClient(
            PaymentProviderProperties props
    ) {
        // Example placeholder – implement like AdyenClient:
        return new StripeClient(props);
    }

}
