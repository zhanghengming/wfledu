package io.dataease.enterprise.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ManagementConfigurationTest {
    private ApplicationContextRunner runner(){return new ApplicationContextRunner().withUserConfiguration(ManagementConfiguration.class);}
    @Test void defaultModeSuppliesNoIdentityOrRequestFilter(){runner().run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(ManagementReadiness.class);assertThat(c).doesNotHaveBean(io.dataease.enterprise.identity.manage.ManagementSessionService.class);});}
    @Test void invalidSwitchRejectsBeforeAssembly(){runner().withPropertyValues("enterprise.management.enabled=yes").run(c->{assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasMessageContaining("explicitly true or false");});}
    @Test void managementWithoutFoundationAndCombinedFullModeReject(){
        runner().withPropertyValues("enterprise.management.enabled=true").run(c->{assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasMessageContaining("requires foundation");});
        runner().withPropertyValues("enterprise.management.enabled=true","enterprise.foundation.enabled=true","enterprise.enabled=true").run(c->{assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasMessageContaining("requires foundation");});
    }
}
