package io.dataease.enterprise.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FoundationConfigurationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> jdbc).withUserConfiguration(FoundationConfiguration.class);

    @Test
    void disabledByDefaultDoesNotTouchDatabase() {
        runner.run(context -> assertThat(context).doesNotHaveBean(EnterpriseFoundationSqlBlock.class));
        assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations())
                .allMatch(call -> call.getMethod().getName().equals("afterPropertiesSet"));
    }

    @Test
    void explicitFalseDoesNotTouchDatabase() {
        runner.withPropertyValues("enterprise.foundation.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EnterpriseFoundationSqlBlock.class));
        assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations())
                .allMatch(call -> call.getMethod().getName().equals("afterPropertiesSet"));
    }

    @Test
    void explicitTrueRegistersIndependentMigrationOnly() {
        runner.withPropertyValues("enterprise.foundation.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(EnterpriseFoundationSqlBlock.class);
            assertThat(context).hasSingleBean(FoundationSchemaVerifier.class);
            assertThat(context.getBean(FoundationSchemaVerifier.class).getOrder()).isGreaterThan(1);
            assertThat(context.getBean(EnterpriseFoundationSqlBlock.class).getVersionGroup()).isEqualTo("4");
            assertThat(context.getBean(EnterpriseFoundationSqlBlock.class).getVersion().getVersion()).isEqualTo("4.1");
        });
        assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations())
                .allMatch(call -> call.getMethod().getName().equals("afterPropertiesSet"));
    }

    @Test
    void malformedSwitchFailsClosedBeforeDatabase() {
        runner.withPropertyValues("enterprise.foundation.enabled=tru")
                .run(context -> assertThat(context).hasFailed());
        assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations())
                .allMatch(call -> call.getMethod().getName().equals("afterPropertiesSet"));
    }

    @Test
    void checkNormalizerPreservesGroupingAndLiteralCase() {
        assertThat(FoundationSchema.normalizeCheck("(a AND (b OR c))"))
                .isNotEqualTo(FoundationSchema.normalizeCheck("((a AND b) OR c)"));
        assertThat(FoundationSchema.normalizeCheck("status='ACTIVE'"))
                .isNotEqualTo(FoundationSchema.normalizeCheck("status='active'"));
    }
}
