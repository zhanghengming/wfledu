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
    void defaultsPreserveLiteralContentsAndOnlyNormalizeKnownFunctionCase() {
        var literal = FoundationSchema.c("status", "varchar(16)", false, "'DISABLED'", "状态");
        assertThat(FoundationSchema.defaultMatches(literal, "DISABLED")).isTrue();
        assertThat(FoundationSchema.defaultMatches(literal, "disabled")).isFalse();
        assertThat(FoundationSchema.defaultMatches(literal, "DISABLED ")).isFalse();
        assertThat(FoundationSchema.defaultMatches(literal, null)).isFalse();
        assertThat(FoundationSchema.defaultMatches(FoundationSchema.c("label", "varchar(64)", false, "'O''Brien'", "名称"), "O'Brien")).isTrue();
        assertThat(FoundationSchema.defaultMatches(FoundationSchema.c("label", "varchar(64)", false, "'CURRENT_TIMESTAMP(6)'", "名称"), "current_timestamp(6)")).isFalse();
        assertThat(FoundationSchema.defaultMatches(FoundationSchema.c("created_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "创建时间"), "current_timestamp(6)")).isTrue();
        assertThat(FoundationSchema.defaultMatches(FoundationSchema.c("created_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "创建时间"), "current_timestamp(3)")).isFalse();
        assertThat(FoundationSchema.defaultMatches(FoundationSchema.c("version", "bigint", false, "1", "版本"), "1")).isTrue();
        var noDefault = FoundationSchema.c("id", "bigint", false, null, "主键");
        assertThat(FoundationSchema.defaultMatches(noDefault, null)).isTrue();
        assertThat(FoundationSchema.defaultMatches(noDefault, "null")).isFalse();
    }

    @Test
    void checkNormalizerPreservesGroupingAndLiteralCase() {
        assertThat(FoundationSchema.normalizeCheck("(a AND (b OR c))"))
                .isNotEqualTo(FoundationSchema.normalizeCheck("((a AND b) OR c)"));
        assertThat(FoundationSchema.normalizeCheck("status='ACTIVE'"))
                .isNotEqualTo(FoundationSchema.normalizeCheck("status='active'"));
        assertThat(FoundationSchema.normalizeCheck("status='ACTIVE'"))
                .isNotEqualTo(FoundationSchema.normalizeCheck("status='ACT_utf8mb4IVE'"));
        assertThat(FoundationSchema.normalizeCheck("status='ACTIVE'"))
                .isNotEqualTo(FoundationSchema.normalizeCheck("status='ACT`IVE'"));
        assertThat(FoundationSchema.normalizeCheck("`status` = _utf8mb4'ACTIVE'"))
                .isEqualTo(FoundationSchema.normalizeCheck("status='ACTIVE'"));
    }
}
