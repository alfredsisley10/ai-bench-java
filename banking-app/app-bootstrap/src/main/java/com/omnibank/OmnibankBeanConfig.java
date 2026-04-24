package com.omnibank;

import com.omnibank.payments.ach.AchCutoffPolicy;
import com.omnibank.payments.wire.WireCutoffPolicy;
import com.omnibank.shared.resilience.BulkheadRegistry;
import com.omnibank.shared.resilience.CircuitBreakerRegistry;
import com.omnibank.shared.resilience.RateLimiterRegistry;
import com.omnibank.shared.resilience.ResilienceMetrics;
import com.omnibank.shared.resilience.TimeLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OmnibankBeanConfig {

    @Bean
    AchCutoffPolicy achCutoffPolicy(Clock clock) {
        return new AchCutoffPolicy(clock);
    }

    @Bean
    WireCutoffPolicy wireCutoffPolicy(Clock clock) {
        return new WireCutoffPolicy(clock);
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry() {
        return new CircuitBreakerRegistry();
    }

    @Bean
    BulkheadRegistry bulkheadRegistry() {
        return new BulkheadRegistry();
    }

    @Bean
    RateLimiterRegistry rateLimiterRegistry() {
        return new RateLimiterRegistry();
    }

    @Bean
    TimeLimiter timeLimiter() {
        return new TimeLimiter();
    }

    @Bean
    ResilienceMetrics resilienceMetrics(CircuitBreakerRegistry cb, BulkheadRegistry bh,
                                         RateLimiterRegistry rl, TimeLimiter tl) {
        return new ResilienceMetrics(cb, bh, rl, tl);
    }
}
