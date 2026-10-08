package io.dataease.enterprise.foundation;

import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.persistence.EnterpriseTenant;
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
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.dataease.enterprise.tenant.domain.OrganizationNode.Kind.*;
import static io.dataease.enterprise.tenant.domain.OrganizationNode.Status.*;
import static org.assertj.core.api.Assertions.*;

/** Real JPA transactions and retained synthetic MySQL schemas; authority/audit are fixture adapters only. */
class OrganizationTransactionTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = EnterpriseUser.class)
    @Import({IDUtils.class, SnowFlake.class})
    static class ScanConfiguration { }

    @BeforeAll
    static void guardDatabase() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }

    private static void rejected(Runnable command, int code) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(DEException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }

    private void fixture(String scenario, Consumer<Fixture> verify) {
        var jdbc = FoundationMigrationTest.migrated(scenario);
        seed(jdbc);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class, ScanConfiguration.class)
                .withBean(DataSource.class, jdbc::getDataSource)
                .withPropertyValues("enterprise.foundation.enabled=true", "spring.jpa.hibernate.ddl-auto=update",
                        "spring.jpa.open-in-view=false", "dataease.machine-id=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    verify.accept(new Fixture(jdbc, context.getBean(EntityManagerFactory.class), context.getBean(JpaTransactionManager.class)));
                });
        FoundationSchema.TABLES.forEach(table -> FoundationSchema.verify(jdbc, table));
        assertThat(AccessContextHolder.current()).isEmpty();
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name,status) VALUES (1,'synthetic','合成用户','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name,status) VALUES (10,'GA','合成集团A','ACTIVE'),(20,'GB','合成集团B','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id,status) VALUES (100,10,1,'ACTIVE'),(200,20,1,'ACTIVE')");
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,school_code,status) VALUES (101,10,'SCHOOL','学校A','0007','ACTIVE'),(201,20,'SCHOOL','学校B','0008','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,status) VALUES (111,10,'DEPARTMENT','部门A','ACTIVE'),(112,10,'DEPARTMENT','部门B','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,parent_id,school_id,status) VALUES (102,10,'DEPARTMENT','校内机构',101,101,'ACTIVE')");
        jdbc.execute("CREATE TABLE w03_organization_audit_fixture(organization_id BIGINT NOT NULL,version BIGINT NOT NULL,"
                + "tenant_id BIGINT NOT NULL,actor_id BIGINT NOT NULL,access_epoch BIGINT NOT NULL,operation VARCHAR(16) NOT NULL,"
                + "occurred_at DATETIME(6) NOT NULL,PRIMARY KEY(organization_id,version)) ENGINE=InnoDB");
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final EntityManagerFactory factory;
        final JpaTransactionManager manager;
        boolean manage = true;
        boolean failAudit;
        final OrganizationTransactionKernel.Authority authority = new OrganizationTransactionKernel.Authority() {
            private void member(EntityManager em, AccessContext access) {
                long count = em.createQuery("SELECT COUNT(m) FROM EnterpriseTenantMember m WHERE m.tenantId=:tenant "
                                + "AND m.userId=:user AND m.status=io.dataease.enterprise.identity.persistence.FoundationStatus.ACTIVE", Long.class)
                        .setParameter("tenant", access.tenantId()).setParameter("user", access.userId()).getSingleResult();
                if (count != 1) throw new DEException(70001, "Fixture membership denied");
            }
            @Override public void requireManage(EntityManager em, AccessContext access) {
                member(em, access);
                if (!manage) throw new DEException(70001, "Fixture management denied");
            }
            @Override public void requireMappingRead(EntityManager em, AccessContext access) { member(em, access); }
        };
        final OrganizationTransactionKernel.Audit audit = (em, change) -> {
            em.createNativeQuery("INSERT INTO w03_organization_audit_fixture VALUES (:org,:version,:tenant,:actor,:epoch,:operation,:time)")
                    .setParameter("org", change.organizationId()).setParameter("version", change.version())
                    .setParameter("tenant", change.tenantId()).setParameter("actor", change.actorId())
                    .setParameter("epoch", change.accessEpoch()).setParameter("operation", change.operation())
                    .setParameter("time", change.occurredAt()).executeUpdate();
            if (failAudit) throw new IllegalStateException("Synthetic audit failure after insertion");
        };
        final OrganizationTransactionKernel kernel;

        Fixture(JdbcTemplate jdbc, EntityManagerFactory factory, JpaTransactionManager manager) {
            this.jdbc = jdbc;
            this.factory = factory;
            this.manager = manager;
            kernel = new OrganizationTransactionKernel(factory, manager, authority, audit,
                    Clock.fixed(Instant.parse("2026-10-08T01:02:03.123456789Z"), ZoneOffset.UTC), 128);
        }

        long number(String sql) { return jdbc.queryForObject(sql, Long.class); }
        long epoch(long tenant) { return jdbc.queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=?", Long.class, tenant); }
        AccessContext access(long tenant) { return new AccessContext(tenant, 1, epoch(tenant), 1); }
        void as(long tenant, Runnable action) {
            try (var scope = AccessContextHolder.open(access(tenant))) { action.run(); }
        }
        void unchanged() {
            assertThat(epoch(10)).isEqualTo(1);
            assertThat(epoch(20)).isEqualTo(1);
            assertThat(number("SELECT COUNT(*) FROM w03_organization_audit_fixture")).isZero();
            assertThat(number("SELECT SUM(version) FROM de_ent_org")).isEqualTo(5);
        }
    }

    private static OrganizationTransactionKernel.Create department(Long parent, Long school, OrganizationNode.Status status) {
        return new OrganizationTransactionKernel.Create(DEPARTMENT, "合成新机构", null, parent, school, status);
    }
    private static OrganizationTransactionKernel.Update move(long id, long version, Long parent) {
        return new OrganizationTransactionKernel.Update(id, version, "合成修改机构", parent, ACTIVE);
    }

    @Test
    void createsSchoolWithAssignedIdExactCodeAndAtomicEpochAudit() {
        fixture("orgcreate", f -> {
            f.as(10, () -> {
                var saved = f.kernel.create(new OrganizationTransactionKernel.Create(SCHOOL, "学校🙂", "School-A", null, null, ACTIVE));
                assertThat(saved.id()).isGreaterThan(201);
                assertThat(saved.version()).isEqualTo(1);
                assertThat(saved.accessEpoch()).isEqualTo(2);
                assertThat(f.jdbc.queryForObject("SELECT school_code FROM de_ent_org WHERE id=?", String.class, saved.id())).isEqualTo("School-A");
                assertThat(f.jdbc.queryForObject("SELECT CAST(updated_at AS CHAR) FROM de_ent_org WHERE id=?", String.class, saved.id())).endsWith("03.123456");
            });
            f.as(10, () -> rejected(() -> f.kernel.create(new OrganizationTransactionKernel.Create(SCHOOL, "重复", "0008", null, null, DISABLED)), 50003));
            assertThat(f.epoch(10)).isEqualTo(2);
            assertThat(f.epoch(20)).isEqualTo(1);
            assertThat(f.number("SELECT COUNT(*) FROM w03_organization_audit_fixture WHERE actor_id=1 AND tenant_id=10 AND access_epoch=2")).isEqualTo(1);
        });
    }

    @Test
    void explicitParentClearUsesCasAndReloadsWithoutLosingImmutableFields() {
        fixture("orgclear", f -> {
            f.as(10, () -> assertThat(f.kernel.update(move(102, 1, null)).version()).isEqualTo(2));
            var row = f.jdbc.queryForMap("SELECT * FROM de_ent_org WHERE id=102");
            assertThat(row.get("parent_id")).isNull();
            assertThat(row.get("school_id")).isEqualTo(101L);
            assertThat(row.get("kind")).isEqualTo("DEPARTMENT");
            assertThat(row.get("tenant_id")).isEqualTo(10L);
            assertThat(f.epoch(10)).isEqualTo(2);
            f.as(10, () -> assertThat(f.kernel.resolveSchoolId(101).schoolCode()).isEqualTo("0007"));
        });
    }

    @Test
    void staleVersionAndEpochRejectWithoutAnyPartialWrites() {
        fixture("orgstale", f -> {
            f.as(10, () -> rejected(() -> f.kernel.update(move(111, 2, null)), 50002));
            f.unchanged();
            var old = f.access(10);
            f.as(10, () -> f.kernel.update(move(111, 1, null)));
            try (var scope = AccessContextHolder.open(old)) { rejected(() -> f.kernel.resolveSchoolId(101), 70001); }
            f.as(10, () -> rejected(() -> f.kernel.update(move(111, 1, null)), 50002));
            assertThat(f.epoch(10)).isEqualTo(2);
            assertThat(f.number("SELECT COUNT(*) FROM w03_organization_audit_fixture")).isEqualTo(1);
            f.jdbc.update("UPDATE de_ent_tenant SET access_epoch=? WHERE id=10", Long.MAX_VALUE);
            f.as(10, () -> rejected(() -> f.kernel.update(move(112, 1, null)), 50002));
            assertThat(f.number("SELECT version FROM de_ent_org WHERE id=112")).isEqualTo(1);
        });
    }

    @Test
    void missingContextAndMissingCollaboratorsNeverFallBackToCommunity() {
        fixture("orgcontext", f -> {
            rejected(() -> f.kernel.resolveSchoolId(101), 20001);
            assertThatThrownBy(() -> new OrganizationTransactionKernel(f.factory, f.manager, null, f.audit, Clock.systemUTC(), 128)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new OrganizationTransactionKernel(f.factory, f.manager, f.authority, null, Clock.systemUTC(), 128)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new OrganizationTransactionKernel(f.factory, new JpaTransactionManager(), f.authority, f.audit, Clock.systemUTC(), 128)).isInstanceOf(IllegalArgumentException.class);
            f.unchanged();
        });
    }

    @Test
    void foreignGroupOrganizationParentAndSchoolRejectInBothDirections() {
        fixture("orgforeign", f -> {
            f.as(10, () -> {
                rejected(() -> f.kernel.update(move(201, 1, null)), 70002);
                rejected(() -> f.kernel.create(department(null, 201L, ACTIVE)), 70002);
                rejected(() -> f.kernel.create(department(201L, null, DISABLED)), 70002);
            });
            f.as(20, () -> {
                rejected(() -> f.kernel.update(move(101, 1, null)), 70002);
                rejected(() -> f.kernel.create(department(null, 101L, ACTIVE)), 70002);
                rejected(() -> f.kernel.create(department(111L, null, DISABLED)), 70002);
            });
            f.unchanged();
        });
    }

    @Test
    void exactSchoolLookupRejectsUnknownForeignAndInvalidCodes() {
        fixture("orglookup", f -> {
            f.as(10, () -> {
                assertThat(f.kernel.resolveSchoolCode("0007").schoolId()).isEqualTo(101);
                rejected(() -> f.kernel.resolveSchoolCode("7"), 70002);
                rejected(() -> f.kernel.resolveSchoolCode("0008"), 70002);
                rejected(() -> f.kernel.resolveSchoolCode(""), 10001);
                rejected(() -> f.kernel.resolveSchoolCode("0007 "), 10001);
                rejected(() -> f.kernel.resolveSchoolId(201), 70002);
                rejected(() -> f.kernel.resolveSchoolId(111), 70002);
                rejected(() -> f.kernel.resolveSchoolId(999), 70002);
            });
            f.as(20, () -> rejected(() -> f.kernel.resolveSchoolCode("0007"), 70002));
            f.unchanged();
        });
    }

    @Test
    void inactiveParentsSchoolsUsersAndTenantsRejectCurrentLookupsOrCommands() {
        fixture("orginactive", f -> {
            f.jdbc.update("UPDATE de_ent_org SET status='DISABLED' WHERE id IN (101,111)");
            f.as(10, () -> {
                rejected(() -> f.kernel.resolveSchoolCode("0007"), 70001);
                rejected(() -> f.kernel.create(department(null, 101L, ACTIVE)), 70001);
                rejected(() -> f.kernel.create(department(111L, null, ACTIVE)), 70001);
            });
            f.jdbc.update("UPDATE de_ent_org SET status='ACTIVE',parent_id=111 WHERE id=101");
            f.as(10, () -> rejected(() -> f.kernel.resolveSchoolId(101), 70001));
            f.jdbc.update("UPDATE de_ent_user SET identity_epoch=2 WHERE id=1");
            f.as(10, () -> rejected(() -> f.kernel.resolveSchoolId(101), 70001));
            f.jdbc.update("UPDATE de_ent_user SET identity_epoch=1,status='DISABLED' WHERE id=1");
            f.as(10, () -> rejected(() -> f.kernel.create(department(null, null, DISABLED)), 70001));
            f.jdbc.update("UPDATE de_ent_user SET status='ACTIVE' WHERE id=1");
            f.jdbc.update("UPDATE de_ent_tenant SET status='DISABLED' WHERE id=10");
            f.as(10, () -> rejected(() -> f.kernel.resolveSchoolId(101), 70001));
            f.unchanged();
        });
    }

    @Test
    void managementAndMembershipAreRequiredEvenWithValidContext() {
        fixture("orgauthority", f -> {
            f.manage = false;
            f.as(10, () -> {
                rejected(() -> f.kernel.create(department(null, null, DISABLED)), 70001);
                assertThat(f.kernel.resolveSchoolId(101).tenantId()).isEqualTo(10);
            });
            f.manage = true;
            f.jdbc.update("UPDATE de_ent_tenant_member SET status='DISABLED' WHERE id=100");
            f.as(10, () -> {
                rejected(() -> f.kernel.create(department(null, null, DISABLED)), 70001);
                rejected(() -> f.kernel.resolveSchoolId(101), 70001);
            });
            f.as(20, () -> assertThat(f.kernel.resolveSchoolId(201).tenantId()).isEqualTo(20));
            f.unchanged();
        });
    }

    @Test
    void auditFailureRollsBackOrganizationVersionEpochAndAuditRowTogether() {
        fixture("orgaudit", f -> {
            f.failAudit = true;
            f.as(10, () -> {
                assertThatThrownBy(() -> f.kernel.update(move(111, 1, 112L))).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> f.kernel.create(department(null, null, DISABLED))).isInstanceOf(IllegalStateException.class);
            });
            f.unchanged();
            assertThat(f.jdbc.queryForObject("SELECT parent_id FROM de_ent_org WHERE id=111", Long.class)).isNull();
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_org")).isEqualTo(5);
        });
    }

    @Test
    void concurrentMutualReparentingCannotCommitCycleAndRetryChecksFreshTree() {
        fixture("orgconcurrent", f -> {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var access = f.access(10);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> concurrent(f, access, ready, start, 111, 112));
                var second = pool.submit(() -> concurrent(f, access, ready, start, 112, 111));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                int a = first.get(15, TimeUnit.SECONDS);
                int b = second.get(15, TimeUnit.SECONDS);
                assertThat(new int[]{a, b}).containsExactlyInAnyOrder(0, 70001);
                f.as(10, () -> rejected(() -> f.kernel.update(a == 0 ? move(112, 1, 111L) : move(111, 1, 112L)), 10001));
                assertThat(f.epoch(10)).isEqualTo(2);
                assertThat(f.number("SELECT COUNT(*) FROM de_ent_org WHERE parent_id IS NOT NULL AND id IN (111,112)")).isEqualTo(1);
                assertThat(f.number("SELECT COUNT(*) FROM w03_organization_audit_fixture")).isEqualTo(1);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                start.countDown();
                pool.shutdownNow();
                try { assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
    }

    private static int concurrent(Fixture f, AccessContext access, CountDownLatch ready, CountDownLatch start, long id, long parent) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try (var scope = AccessContextHolder.open(access)) {
            try { f.kernel.update(move(id, 1, parent)); return 0; }
            catch (DEException failure) { return failure.getCode(); }
        } finally { assertThat(AccessContextHolder.current()).isEmpty(); }
    }

    @Test
    void activeOrganizationCannotReferenceDepartmentAsSchoolOrMoveAcrossSchoolScope() {
        fixture("orgscope", f -> {
            f.as(10, () -> {
                rejected(() -> f.kernel.create(department(null, 111L, DISABLED)), 10001);
                rejected(() -> f.kernel.update(move(102, 1, 111L)), 10001);
                rejected(() -> f.kernel.update(move(111, 1, 101L)), 10001);
                rejected(() -> f.kernel.update(move(101, 1, 102L)), 10001);
            });
            f.unchanged();
        });
    }

    @Test
    void ambientTransactionAndStalePersistenceContextCannotBypassChecks() {
        fixture("orgambient", f -> {
            f.as(10, () -> assertThatThrownBy(() -> new TransactionTemplate(f.manager).execute(status ->
                    f.kernel.update(move(111, 1, null)))).isInstanceOf(IllegalStateException.class));
            f.unchanged();
            var em = f.factory.createEntityManager();
            try {
                em.find(EnterpriseTenant.class, 10L);
                var old = f.access(10);
                f.jdbc.update("UPDATE de_ent_tenant SET access_epoch=2 WHERE id=10");
                TransactionSynchronizationManager.bindResource(f.factory, new EntityManagerHolder(em));
                try (var scope = AccessContextHolder.open(old)) { rejected(() -> f.kernel.resolveSchoolId(101), 70001); }
                finally { TransactionSynchronizationManager.unbindResource(f.factory); }
                assertThat(f.number("SELECT COUNT(*) FROM w03_organization_audit_fixture")).isZero();
            } finally { em.close(); }
        });
    }
}
