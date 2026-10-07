package io.dataease.enterprise.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class EnterpriseSecurityConfiguration {

    @Bean
    static EnterpriseAssemblyGuard enterpriseAssemblyGuard(Environment environment) {
        return new EnterpriseAssemblyGuard(environment);
    }
}
