package io.dataease.enterprise.foundation;

import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.hibernate.cfg.SchemaToolingSettings.HBM2DDL_FILTER_PROVIDER;

@Configuration(proxyBeanMethods = false)
public class EnterpriseJpaConfiguration {
    private static final String INTEGRATOR_PROVIDER = "hibernate.integrator_provider";

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    HibernatePropertiesCustomizer enterpriseSchemaOwnershipCustomizer(Environment environment) {
        var filter = new EnterpriseSchemaFilterProvider();
        var guard = new EnterpriseMappingGuard(filter,
                "true".equals(environment.getProperty("enterprise.foundation.enabled", "false")));
        return properties -> {
            if (properties.containsKey(HBM2DDL_FILTER_PROVIDER) || properties.containsKey(INTEGRATOR_PROVIDER)) {
                throw new IllegalStateException("Enterprise JPA schema ownership conflicts with an existing provider");
            }
            properties.put(HBM2DDL_FILTER_PROVIDER, filter);
            properties.put(INTEGRATOR_PROVIDER, (IntegratorProvider) () -> List.of(guard));
        };
    }
}
