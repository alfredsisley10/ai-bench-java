package com.omnibank.shared.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "omnibankAuditorAware")
public class AuditingConfig {

    @Component("omnibankAuditorAware")
    public static class StaticAuditor implements AuditorAware<String> {

        private final ObjectProvider<PrincipalProvider> principalProvider;

        public StaticAuditor(ObjectProvider<PrincipalProvider> principalProvider) {
            this.principalProvider = principalProvider;
        }

        @Override
        public Optional<String> getCurrentAuditor() {
            PrincipalProvider resolver = principalProvider.getIfAvailable();
            if (resolver == null) {
                return Optional.of("system");
            }
            return Optional.of(resolver.currentActor());
        }
    }

    public interface PrincipalProvider {
        String currentActor();
    }
}
