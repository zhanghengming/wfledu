package io.dataease.enterprise.bootstrap;

import io.dataease.api.permissions.auth.api.ResourceAuthApi;
import io.dataease.api.permissions.dataset.api.ColumnPermissionsApi;
import io.dataease.api.permissions.dataset.api.RowPermissionsApi;
import io.dataease.api.permissions.login.api.LoginApi;
import io.dataease.api.permissions.enterprise.AccessContextResolver;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks assembly before ordinary singleton initialization, without creating security beans.
 * This is not a substitute for authorization on individual resource and data operations.
 */
final class EnterpriseAssemblyGuard implements BeanFactoryPostProcessor, PriorityOrdered {

    private static final List<Class<?>> REQUIRED_APIS = List.of(
            LoginApi.class, ResourceAuthApi.class, RowPermissionsApi.class, ColumnPermissionsApi.class, AccessContextResolver.class);
    private static final String COMMUNITY_LOGIN_CONFIG = "io.dataease.auth.config.SubstituleLoginConfig";
    private static final String COMMUNITY_PERMISSIONS_PREFIX = "io.dataease.substitute.permissions.";
    private final Environment environment;

    EnterpriseAssemblyGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String configured = environment.getProperty("enterprise.enabled");
        if (configured == null || "false".equalsIgnoreCase(configured.trim())) {
            return;
        }
        if (!"true".equalsIgnoreCase(configured.trim())) {
            throw new IllegalStateException("enterprise.enabled must be explicitly true or false");
        }

        List<String> problems = new ArrayList<>();
        for (Class<?> api : REQUIRED_APIS) {
            String[] names = beanFactory.getBeanNamesForType(api, true, false);
            if (names.length != 1) {
                problems.add(api.getSimpleName() + " requires exactly one implementation (found " + names.length + ")");
            }
        }
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            Class<?> type = beanFactory.getType(name, false);
            if (isCommunityType(definition.getBeanClassName())
                    || (type != null && isCommunityType(type.getName()))
                    || "substituleLoginData".equals(name)) {
                problems.add("community security fallback is registered: " + name);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Enterprise security assembly rejected: " + String.join("; ", problems));
        }
    }

    private boolean isCommunityType(String className) {
        return className != null && (COMMUNITY_LOGIN_CONFIG.equals(className)
                || className.startsWith(COMMUNITY_PERMISSIONS_PREFIX));
    }
}
