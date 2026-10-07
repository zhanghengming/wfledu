package io.dataease.enterprise.bootstrap;

import io.dataease.api.permissions.auth.api.ResourceAuthApi;
import io.dataease.api.permissions.dataset.api.ColumnPermissionsApi;
import io.dataease.api.permissions.dataset.api.RowPermissionsApi;
import io.dataease.api.permissions.login.api.LoginApi;
import io.dataease.auth.config.SubstituleLoginConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EnterpriseAssemblyGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnterpriseSecurityConfiguration.class);

    @Test
    void communityDefaultDoesNotRequireEnterpriseServices() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void explicitDisabledModeDoesNotRequireEnterpriseServices() {
        runner.withPropertyValues("enterprise.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void malformedSwitchCannotSilentlyFallBackToCommunity() {
        for (String value : new String[]{"tru", "", "1"}) {
            runner.withPropertyValues("enterprise.enabled=" + value).run(context ->
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("enterprise.enabled must be explicitly true or false"));
        }
    }

    @Test
    void missingServicesRejectBeforeSideEffectBeanInitialization() {
        AtomicInteger initialized = new AtomicInteger();
        runner.withPropertyValues("enterprise.enabled=true")
                .withBean("databaseSideEffect", String.class, () -> {
                    initialized.incrementAndGet();
                    return "must not initialize";
                }).run(context -> {
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("LoginApi")
                            .hasMessageContaining("ResourceAuthApi")
                            .hasMessageContaining("RowPermissionsApi")
                            .hasMessageContaining("ColumnPermissionsApi");
                    assertThat(initialized).hasValue(0);
                });
    }

    @Test
    void guardRunsBeforeUnorderedPostProcessorsAreCreated() {
        AtomicInteger initialized = new AtomicInteger();
        runner.withPropertyValues("enterprise.enabled=true")
                .withBean("ordinaryPostProcessor", BeanFactoryPostProcessor.class, () -> {
                    initialized.incrementAndGet();
                    return beanFactory -> { };
                }).run(context -> {
                    assertThat(context.getStartupFailure()).hasMessageContaining("Enterprise security assembly rejected");
                    assertThat(initialized).hasValue(0);
                });
    }

    @Test
    void eachRequiredServiceIsMandatory() {
        Class<?>[] apis = {LoginApi.class, ResourceAuthApi.class, RowPermissionsApi.class, ColumnPermissionsApi.class};
        for (Class<?> absent : apis) {
            ApplicationContextRunner configured = runner.withPropertyValues("enterprise.enabled=true");
            if (absent != LoginApi.class) {
                configured = configured.withBean("loginServer", LoginApi.class, () -> mock(LoginApi.class));
            }
            if (absent != ResourceAuthApi.class) {
                configured = configured.withBean(ResourceAuthApi.class, () -> mock(ResourceAuthApi.class));
            }
            if (absent != RowPermissionsApi.class) {
                configured = configured.withBean(RowPermissionsApi.class, () -> mock(RowPermissionsApi.class));
            }
            if (absent != ColumnPermissionsApi.class) {
                configured = configured.withBean(ColumnPermissionsApi.class, () -> mock(ColumnPermissionsApi.class));
            }
            configured.run(context -> assertThat(context.getStartupFailure())
                    .hasMessageContaining(absent.getSimpleName() + " requires exactly one implementation (found 0)"));
        }
    }

    @Test
    void beanNameAloneDoesNotProveAuthenticationCapability() {
        runner.withPropertyValues("enterprise.enabled=true")
                .withBean("loginServer", Object.class, Object::new)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("LoginApi requires exactly one implementation (found 0)"));
    }

    @Test
    void ambiguousAuthenticationIsRejected() {
        completeAssembly().withBean("anotherLoginServer", LoginApi.class, () -> mock(LoginApi.class))
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("LoginApi requires exactly one implementation (found 2)"));
    }

    @Test
    void communityLoginConfigurationIsRejectedBeforeGeneratingCredentials() {
        AtomicInteger initialized = new AtomicInteger();
        completeAssembly().withBean("legacyLoginConfiguration", SubstituleLoginConfig.class, () -> {
            initialized.incrementAndGet();
            return new SubstituleLoginConfig();
        }).run(context -> {
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("community security fallback is registered: legacyLoginConfiguration");
            assertThat(initialized).hasValue(0);
        });
    }

    @Test
    void fallbackDataCannotCoexistWithEnterpriseServices() {
        completeAssembly().withBean("substituleLoginData", Object.class, Object::new)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("community security fallback is registered: substituleLoginData"));
    }

    @Test
    void uniqueTypedServicesPassAssemblyCheckOnly() {
        completeAssembly().run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner completeAssembly() {
        // A typed adapter with a different name must not make the legacy name-based fallback safe.
        return runner.withPropertyValues("enterprise.enabled=true")
                .withBean("typedEnterpriseLogin", LoginApi.class, () -> mock(LoginApi.class))
                .withBean(ResourceAuthApi.class, () -> mock(ResourceAuthApi.class))
                .withBean(RowPermissionsApi.class, () -> mock(RowPermissionsApi.class))
                .withBean(ColumnPermissionsApi.class, () -> mock(ColumnPermissionsApi.class));
    }
}
