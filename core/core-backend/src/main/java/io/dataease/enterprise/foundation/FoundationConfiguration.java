package io.dataease.enterprise.foundation;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class FoundationConfiguration {
    @Bean
    static BeanFactoryPostProcessor foundationSwitchGuard(Environment environment) {
        return factory -> {
            String value = environment.getProperty("enterprise.foundation.enabled", "false");
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new IllegalStateException("enterprise.foundation.enabled must be explicitly true or false");
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "enterprise.foundation.enabled", havingValue = "true")
    FoundationSchemaVerifier foundationSchemaVerifier(EnterpriseFoundationSqlBlock block) {
        return new FoundationSchemaVerifier(block);
    }

    @Bean
    @ConditionalOnProperty(name = "enterprise.foundation.enabled", havingValue = "true")
    EnterpriseFoundationSqlBlock enterpriseFoundationSqlBlock(JdbcTemplate jdbc) {
        return new EnterpriseFoundationSqlBlock(jdbc);
    }
}
