package io.dataease.enterprise.foundation;

import io.dataease.enterprise.audit.manage.OrganizationAuditAppender;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.persistence.EnterpriseUser;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.enterprise.tenant.manage.OrganizationTransactionKernel;
import io.dataease.exception.DEException;
import io.dataease.utils.IDUtils;
import io.dataease.utils.SnowFlake;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Production audit adapter and JPA transactions. Management authority remains an explicit fixture. */
class OrganizationAuditTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {EnterpriseUser.class, EnterpriseAuditEvent.class})
    @Import({IDUtils.class, SnowFlake.class})
    static class Scan { }

    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }

    private void fixture(String name, Consumer<Fixture> check) {
        var jdbc = FoundationMigrationTest.migrated(name);
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name,status) VALUES(1,'synthetic','合成用户','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name,status) VALUES(10,'GA','集团A','ACTIVE'),(20,'GB','集团B','ACTIVE')");
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class, Scan.class).withBean(DataSource.class, jdbc::getDataSource)
                .withPropertyValues("enterprise.foundation.enabled=true", "spring.jpa.hibernate.ddl-auto=update", "dataease.machine-id=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    check.accept(new Fixture(jdbc, context.getBean(EntityManagerFactory.class), context.getBean(JpaTransactionManager.class)));
                });
        new FoundationSchemaVerifier(jdbc).run(null);
        assertThat(AccessContextHolder.current()).isEmpty();
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final EntityManagerFactory factory;
        final JpaTransactionManager manager;
        String trace = "synthetic-trace";
        boolean failAfterInsert;
        final OrganizationAuditAppender appender;
        final OrganizationTransactionKernel kernel;

        Fixture(JdbcTemplate jdbc, EntityManagerFactory factory, JpaTransactionManager manager) {
            this.jdbc = jdbc; this.factory = factory; this.manager = manager;
            appender = new OrganizationAuditAppender(factory, () -> trace);
            // Intentionally a test authority, never registered in production.
            var authority = new OrganizationTransactionKernel.Authority() {
                @Override public void requireManage(EntityManager em, AccessContext access) { }
                @Override public void requireMappingRead(EntityManager em, AccessContext access) { }
            };
            kernel = new OrganizationTransactionKernel(factory, manager, authority, (em, change) -> {
                appender.append(em, change);
                if (failAfterInsert) { em.flush(); throw new IllegalStateException("Synthetic failure after real audit insertion"); }
            }, Clock.systemUTC(), 128);
        }

        long number(String sql) { return jdbc.queryForObject(sql, Long.class); }
        AccessContext access(long tenant) { return new AccessContext(tenant, 1, jdbc.queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=?", Long.class, tenant), 1); }
        void as(long tenant, Runnable action) { try (var scope = AccessContextHolder.open(access(tenant))) { action.run(); } }
        OrganizationTransactionKernel.Create create() {
            return new OrganizationTransactionKernel.Create(OrganizationNode.Kind.DEPARTMENT, "合成机构敏感名称不得入审计", null, null, null, OrganizationNode.Status.ACTIVE);
        }
        void transaction(Consumer<EntityManager> command) {
            new TransactionTemplate(manager).executeWithoutResult(status -> command.accept(EntityManagerFactoryUtils.getTransactionalEntityManager(factory)));
        }
        OrganizationTransactionKernel.Change change(long tenant, long actor, long epoch, String operation) {
            return new OrganizationTransactionKernel.Change(tenant, 1, actor, 1, epoch, operation, LocalDateTime.of(2026, 10, 8, 1, 2));
        }
    }

    @Test void organizationCreateAndUpdateCommitWithFormalAudit() {
        fixture("auditsuccess", f -> {
            f.as(10, () -> {
                var saved = f.kernel.create(f.create());
                assertThat(saved.accessEpoch()).isEqualTo(2);
                assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event WHERE event_type='ORGANIZATION_CREATED' AND access_epoch=2")).isEqualTo(1);
                assertThat(f.jdbc.queryForObject("SELECT JSON_EXTRACT(details,'$.version') FROM de_ent_audit_event", String.class)).isEqualTo("1");
            });
            long id = f.jdbc.queryForObject("SELECT id FROM de_ent_org", Long.class);
            f.as(10, () -> f.kernel.update(new OrganizationTransactionKernel.Update(id, 1, "修改", null, OrganizationNode.Status.ACTIVE)));
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event WHERE tenant_id=10 AND actor_user_id=1 AND result_code='SUCCESS' AND resource_type='ORGANIZATION'")).isEqualTo(2);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event WHERE access_epoch=3 AND event_type='ORGANIZATION_UPDATED'")).isEqualTo(1);
            assertThat(f.jdbc.queryForList("SELECT details FROM de_ent_audit_event", String.class)).allSatisfy(json -> {
                assertThat(json).contains("version").doesNotContain("名称", "password", "token");
            });
        });
    }

    @Test void auditFailureAfterInsertRollsBackCreateAndEpoch() {
        fixture("auditrollbackcreate", f -> {
            f.failAfterInsert = true;
            f.as(10, () -> assertThatThrownBy(() -> f.kernel.create(f.create())).hasMessageContaining("after real audit"));
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_org")).isZero();
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event")).isZero();
            assertThat(f.number("SELECT access_epoch FROM de_ent_tenant WHERE id=10")).isEqualTo(1);
        });
    }

    @Test void auditFailureAfterInsertRollsBackUpdateAndEpoch() {
        fixture("auditrollbackupdate", f -> {
            f.as(10, () -> f.kernel.create(f.create()));
            long id = f.jdbc.queryForObject("SELECT id FROM de_ent_org", Long.class);
            f.failAfterInsert = true;
            f.as(10, () -> assertThatThrownBy(() -> f.kernel.update(new OrganizationTransactionKernel.Update(id, 1, "未提交修改", null, OrganizationNode.Status.ACTIVE))).hasMessageContaining("after real audit"));
            assertThat(f.number("SELECT version FROM de_ent_org")).isEqualTo(1);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event")).isEqualTo(1);
            assertThat(f.number("SELECT access_epoch FROM de_ent_tenant WHERE id=10")).isEqualTo(2);
        });
    }

    @Test void invalidTraceAndOperationCannotLeaveAuditOrOrganization() {
        fixture("audittrace", f -> {
            for (String trace : List.of("", " leading", "token=private", "x".repeat(65))) {
                f.trace = trace;
                f.as(10, () -> assertThatThrownBy(() -> f.kernel.create(f.create())).isInstanceOf(DEException.class));
            }
            f.trace = "valid";
            f.as(10, () -> f.transaction(em -> assertThatThrownBy(() -> f.appender.append(em, f.change(10, 1, 2, "UNKNOWN"))).isInstanceOf(DEException.class)));
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_org")).isZero();
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event")).isZero();
            assertThat(f.number("SELECT access_epoch FROM de_ent_tenant WHERE id=10")).isEqualTo(1);
        });
    }

    @Test void forgedChangeScopeActorAndEpochRejects() {
        fixture("auditforged", f -> f.as(10, () -> f.transaction(em -> {
            for (var change : List.of(f.change(20, 1, 2, "CREATE"), f.change(10, 999, 2, "CREATE"), f.change(10, 1, 1, "CREATE"),
                    f.change(10, 1, 2, "CREATE"))) // Even matching shape cannot invent an organization mutation.
                assertThatThrownBy(() -> f.appender.append(em, change)).isInstanceOf(DEException.class);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event")).isZero();
        })));
    }

    @Test void missingContextUnboundTransactionAndWrongFactoryReject() {
        fixture("auditcontext", f -> {
            assertThatThrownBy(() -> f.appender.append(null, f.change(10, 1, 2, "CREATE"))).isInstanceOf(DEException.class);
            f.as(10, () -> {
                assertThatThrownBy(() -> f.appender.append(null, f.change(10, 1, 2, "CREATE"))).isInstanceOf(IllegalStateException.class);
                f.transaction(em -> {
                    try (var other = f.factory.createEntityManager()) {
                        assertThatThrownBy(() -> f.appender.append(other, f.change(10, 1, 2, "CREATE"))).isInstanceOf(IllegalStateException.class);
                    }
                    var wrong = new OrganizationAuditAppender(mock(EntityManagerFactory.class), () -> "valid");
                    assertThatThrownBy(() -> wrong.append(em, f.change(10, 1, 2, "CREATE"))).isInstanceOf(IllegalStateException.class);
                });
            });
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event")).isZero();
        });
    }

    @Test void twoGroupsKeepTheirOrganizationAuditSeparated() {
        fixture("auditgroups", f -> {
            f.as(10, () -> f.kernel.create(f.create()));
            f.as(20, () -> f.kernel.create(f.create()));
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event WHERE tenant_id=10")).isEqualTo(1);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event WHERE tenant_id=20")).isEqualTo(1);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event a JOIN de_ent_org o ON a.resource_id=o.id WHERE a.tenant_id<>o.tenant_id")).isZero();
            f.as(20, () -> f.transaction(em -> assertThatThrownBy(() -> f.appender.append(em, f.change(10, 1, 3, "CREATE"))).isInstanceOf(DEException.class)));
        });
    }

    @Test void mappingIsImmutableAndRemovalIsRejected() {
        fixture("auditimmutable", f -> {
            f.as(10, () -> f.kernel.create(f.create()));
            long id = f.jdbc.queryForObject("SELECT id FROM de_ent_audit_event", Long.class);
            f.transaction(em -> {
                var audit = em.find(EnterpriseAuditEvent.class, id);
                ReflectionTestUtils.setField(audit, "details", "{\"forged\":true}"); em.flush();
            });
            assertThat(f.jdbc.queryForObject("SELECT JSON_EXTRACT(details,'$.version') FROM de_ent_audit_event", String.class)).isEqualTo("1");
            assertThatThrownBy(() -> f.transaction(em -> em.remove(em.find(EnterpriseAuditEvent.class, id)))).hasMessageContaining("append-only");
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event")).isEqualTo(1);
        });
    }
}
