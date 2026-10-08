package io.dataease.enterprise.foundation;

import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.extensions.datasource.utils.SpringContextUtil;
import io.dataease.listener.InitSqlListener;
import io.dataease.migrationfixture.W03VersionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Formal 4.1/4.2 with the real JPA version repository, merge aspect and listener. */
class AuditMigrationTest {
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }

    private void fixture(String scenario, BiConsumer<JdbcTemplate, W03VersionRepository> check) {
        var jdbc = FoundationMigrationTest.fresh(scenario);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class, EnterpriseMigrationEvolutionTest.RepositoryConfiguration.class)
                .withBean(DataSource.class, jdbc::getDataSource)
                .withBean(PersistenceManagedTypes.class, () -> PersistenceManagedTypes.of(DeStandaloneVersion.class.getName()))
                .withBean(EnterpriseFoundationSqlBlock.class, () -> new EnterpriseFoundationSqlBlock(jdbc))
                .withBean(EnterpriseAuditSqlBlock.class, () -> new EnterpriseAuditSqlBlock(jdbc))
                .withPropertyValues("enterprise.foundation.enabled=true", "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var previous = SpringContextUtil.getApplicationContext();
                    try {
                        new SpringContextUtil().setApplicationContext(context.getSourceApplicationContext());
                        check.accept(jdbc, context.getBean(W03VersionRepository.class));
                    } finally { new SpringContextUtil().setApplicationContext(previous); }
                });
    }

    private static void run(W03VersionRepository versions) {
        var listener = new InitSqlListener();
        ReflectionTestUtils.setField(listener, "deStandaloneVersionRepository", versions);
        listener.run(null);
    }

    @Test void formalPlanCreatesFiveEmptyTablesAndRestartsWithoutNewRecords() {
        fixture("auditplan", (jdbc, versions) -> {
            run(versions); new FoundationSchemaVerifier(jdbc, FoundationSchemaV42.TABLES).run(null); run(versions);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.2", "4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsOnly(true);
            for (var table : FoundationSchemaV42.TABLES) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `" + table.name() + "`", Long.class)).isZero();
        });
    }

    @Test void formalUpgradePreservesV41RowsAndDoesNotReexecuteSuccessfulMigration() {
        fixture("auditupgrade", (jdbc, versions) -> {
            // Instead of mutating bean definitions, create the real 4.1 success record through the listener in a bounded old context.
            var current = SpringContextUtil.getApplicationContext();
            try (var old = new GenericApplicationContext()) {
                old.registerBean(EnterpriseFoundationSqlBlock.class, () -> new EnterpriseFoundationSqlBlock(jdbc)); old.refresh();
                new SpringContextUtil().setApplicationContext(old); run(versions);
            } finally { new SpringContextUtil().setApplicationContext(current); }
            jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'retained','合成保留')");
            run(versions); new FoundationSchemaVerifier(jdbc, FoundationSchemaV42.TABLES).run(null);
            assertThat(jdbc.queryForObject("SELECT username FROM de_ent_user WHERE id=1", String.class)).isEqualTo("retained");
            assertThat(versions.findRecords()).hasSize(2);
        });
    }

    @Test void committedAuditDdlFailureRetriesAndRetainsFailureRecord() {
        fixture("auditretry", (jdbc, versions) -> {
            var current = SpringContextUtil.getApplicationContext();
            var interrupted = spy(jdbc); var fail = new AtomicBoolean(true);
            doAnswer(call -> {
                String sql = call.getArgument(0); jdbc.execute(sql);
                if (sql.startsWith("CREATE TABLE `de_ent_audit_event`") && fail.getAndSet(false)) throw new IllegalStateException("synthetic failure after committed audit DDL");
                return null;
            }).when(interrupted).execute(anyString());
            try (var ctx = new GenericApplicationContext()) {
                ctx.registerBean(EnterpriseFoundationSqlBlock.class, () -> new EnterpriseFoundationSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuditSqlBlock.class, () -> new EnterpriseAuditSqlBlock(interrupted)); ctx.refresh();
                new SpringContextUtil().setApplicationContext(ctx);
                assertThatThrownBy(() -> run(versions)).hasMessageContaining("4.2");
            } finally { new SpringContextUtil().setApplicationContext(current); }
            FoundationSchema.verify(jdbc, FoundationSchemaV42.AUDIT);
            run(versions); run(versions); new FoundationSchemaVerifier(jdbc, FoundationSchemaV42.TABLES).run(null);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.2", "4.2", "4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsExactly(true, false, true);
        });
    }

    @Test void existingAuditDriftFailsWithoutRepairOrChangingRows() {
        var jdbc = FoundationMigrationTest.migrated("auditdrift");
        jdbc.execute("ALTER TABLE de_ent_audit_event ALTER CHECK ck_audit_scope NOT ENFORCED");
        var before = jdbc.queryForMap("SHOW CREATE TABLE de_ent_audit_event");
        assertThatThrownBy(() -> new EnterpriseAuditSqlBlock(jdbc).execute()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FoundationSchemaVerifier(jdbc).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("SHOW CREATE TABLE de_ent_audit_event")).isEqualTo(before);
    }

    private static String insert(String scope, String tenant, String actor, String user, String epoch, String details) {
        return "INSERT INTO de_ent_audit_event(id,event_scope,tenant_id,actor_kind,actor_user_id,event_type,result_code,trace_id,access_epoch,details) "
                + "VALUES(1,'" + scope + "'," + tenant + ",'" + actor + "'," + user + ",'PROBE','SUCCESS','synthetic'," + epoch + "," + details + ")";
    }

    private static void integrity(Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                error -> assertThat(((SQLException) error.getRootCause()).getErrorCode()).isIn(3819, 1452, 1062, 3140, 1048));
    }

    @Test void nullableScopeAndActorBranchesRejectIncompleteRows() {
        var jdbc = FoundationMigrationTest.migrated("auditbranches");
        for (String sql : List.of(insert("TENANT", "NULL", "SYSTEM", "NULL", "1", "'{}'"),
                insert("GLOBAL", "NULL", "USER", "NULL", "NULL", "'{}'"),
                insert("GLOBAL", "NULL", "SYSTEM", "NULL", "1", "'{}'"),
                insert("UNKNOWN", "NULL", "SYSTEM", "NULL", "NULL", "'{}'"))) integrity(() -> jdbc.update(sql));
        jdbc.update(insert("GLOBAL", "NULL", "SYSTEM", "NULL", "NULL", "'{}'"));
    }

    @Test void jsonMustBeObjectAndWithinBoundedStorage() {
        var jdbc = FoundationMigrationTest.migrated("auditjson");
        for (String json : List.of("'[]'", "'null'", "'broken'", "JSON_OBJECT('payload',REPEAT('x',4100))",
                "JSON_OBJECT('payload',REPEAT('中',1400))")) // UTF-8 bytes, not character count.
            integrity(() -> jdbc.update(insert("GLOBAL", "NULL", "SYSTEM", "NULL", "NULL", json)));
    }

    @Test void auditForeignKeysRejectUnknownTenantAndActor() {
        var jdbc = FoundationMigrationTest.migrated("auditfk");
        for (String sql : List.of(insert("TENANT", "999", "SYSTEM", "NULL", "1", "'{}'"),
                insert("GLOBAL", "NULL", "USER", "999", "NULL", "'{}'"))) {
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOfSatisfying(org.springframework.dao.DataIntegrityViolationException.class,
                    error -> {
                        var cause = (SQLException) error.getRootCause();
                        assertThat(cause.getSQLState()).isEqualTo("23000");
                        assertThat(cause.getErrorCode()).isIn(1216, 1452);
                    });
        }
    }

    @Test void dictionaryShapeHasThirteenColumnsTwoForeignKeysAndImmutableChecks() {
        var jdbc = FoundationMigrationTest.migrated("auditshape");
        assertThat(FoundationSchemaV42.AUDIT.columns()).extracting(FoundationSchema.Column::name).containsExactly(
                "id", "created_at", "event_scope", "tenant_id", "actor_kind", "actor_user_id", "event_type", "resource_type", "resource_id", "result_code", "trace_id", "access_epoch", "details");
        assertThat(FoundationSchemaV42.AUDIT.foreignKeys()).hasSize(2);
        assertThat(FoundationSchemaV42.AUDIT.keys()).hasSize(4);
        assertThat(FoundationSchemaV42.AUDIT.checks()).hasSize(6);
        assertThatThrownBy(() -> FoundationSchemaV42.AUDIT.checks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_audit_event' AND COLUMN_NAME='details'", String.class)).isEqualTo("json");
        new FoundationSchemaVerifier(jdbc).run(null);
    }
}
