package io.dataease.enterprise.foundation;

import io.dataease.enterprise.identity.persistence.EnterpriseOrganization;
import io.dataease.enterprise.identity.persistence.EnterpriseTenant;
import io.dataease.enterprise.identity.persistence.EnterpriseTenantMember;
import io.dataease.enterprise.identity.persistence.EnterpriseUser;
import io.dataease.enterprise.identity.persistence.FoundationRecord;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.jpafixture.CommunityMappingProbe;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/** Production entities, Boot's actual scanner and the authorized retained MySQL 8 schemas. */
class FoundationJpaMappingTest {
    private static final long USER = 910000000000001001L;
    private static final long TENANT = USER + 10;
    private static final long SCHOOL = USER + 20;
    private static final long MEMBER = USER + 30;
    private static final LocalDateTime TIME = LocalDateTime.parse("2026-10-08T01:02:03.123456");
    private static final Map<Class<?>, String> TABLES = Map.of(
            EnterpriseUser.class, "de_ent_user", EnterpriseTenant.class, "de_ent_tenant",
            EnterpriseTenantMember.class, "de_ent_tenant_member", EnterpriseOrganization.class, "de_ent_org");

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {EnterpriseUser.class, CommunityMappingProbe.class})
    static class ScanConfiguration {
    }

    @BeforeAll
    static void guardDatabase() throws Exception {
        FoundationMigrationTest.guardedPrivateConnection();
    }

    private ApplicationContextRunner runner(JdbcTemplate jdbc, String... settings) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class, ScanConfiguration.class)
                .withBean(DataSource.class, jdbc::getDataSource)
                .withPropertyValues("spring.jpa.hibernate.ddl-auto=update", "spring.jpa.open-in-view=false")
                .withPropertyValues(settings);
    }

    private void mapped(String scenario, Consumer<EntityManagerFactory> check) {
        JdbcTemplate jdbc = FoundationMigrationTest.migrated(scenario);
        Map<String, String> before = structure(jdbc);
        runner(jdbc, "enterprise.foundation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            check.accept(context.getBean(EntityManagerFactory.class));
        });
        assertThat(structure(jdbc)).isEqualTo(before);
        FoundationSchema.TABLES.forEach(table -> FoundationSchema.verify(jdbc, table));
    }

    private static Map<String, String> structure(JdbcTemplate jdbc) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var table : FoundationSchema.TABLES) {
            result.put(table.name(), (String) jdbc.queryForMap("SHOW CREATE TABLE `" + table.name() + "`").get("Create Table"));
        }
        return result;
    }

    private static void transaction(EntityManagerFactory factory, Consumer<EntityManager> command) {
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                command.accept(em);
                em.getTransaction().commit();
            } catch (RuntimeException | Error failure) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw failure;
            }
        }
    }

    private static <T extends FoundationRecord> T audit(T record, long id) {
        record.setId(id);
        record.setCreatedAt(TIME);
        record.setUpdatedAt(TIME);
        return record;
    }

    private static EnterpriseTenant tenant(long id, String code) {
        var tenant = audit(new EnterpriseTenant(), id);
        tenant.setCode(code);
        tenant.setName("合成集团🙂");
        tenant.setCreatedBy(USER);
        tenant.setUpdatedBy(USER);
        return tenant;
    }

    private static EnterpriseOrganization school(long id, long tenantId, String code) {
        var school = audit(new EnterpriseOrganization(), id);
        school.setTenantId(tenantId);
        school.setKind(EnterpriseOrganization.Kind.SCHOOL);
        school.setName("合成学校");
        school.setSchoolCode(code);
        school.setCreatedBy(USER);
        school.setUpdatedBy(USER);
        return school;
    }

    private static EnterpriseTenantMember member(long id, long tenantId) {
        var member = audit(new EnterpriseTenantMember(), id);
        member.setTenantId(tenantId);
        member.setUserId(USER);
        member.setCreatedBy(USER);
        member.setUpdatedBy(USER);
        return member;
    }

    private static void seed(EntityManagerFactory factory) {
        transaction(factory, em -> {
            var user = audit(new EnterpriseUser(), USER);
            user.setUsername("Synthetic-A");
            user.setDisplayName("合成用户🙂");
            user.setLegacyUid(USER + 100);
            em.persist(user);
            em.flush();
            em.persist(tenant(TENANT, "Group-A"));
            em.flush();
            em.persist(school(SCHOOL, TENANT, "0007"));
            em.persist(member(MEMBER, TENANT));
            em.flush();
        });
    }

    private static List<Class<?>> entities(EntityManagerFactory factory) {
        List<Class<?>> result = new ArrayList<>();
        factory.getMetamodel().getEntities().forEach(entity -> result.add(entity.getJavaType()));
        return result;
    }

    private static void assertForeignKeyFailure(Throwable failure) {
        assertThat(failure).isInstanceOf(PersistenceException.class);
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root).isInstanceOf(SQLException.class);
        var sql = (SQLException) root;
        assertThat(sql.getSQLState()).isEqualTo("23000");
        // MySQL may omit table/constraint names for accounts without table-level metadata privileges.
        assertThat(sql.getErrorCode()).isIn(1216, 1452);
    }

    @Test
    void defaultAutomaticScanExcludesEnterpriseAndKeepsCommunity() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("mappingdefault");
        runner(jdbc).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(entities(context.getBean(EntityManagerFactory.class))).containsExactly(CommunityMappingProbe.class);
        });
        assertThat(jdbc.queryForList("SHOW TABLES", String.class)).containsExactlyInAnyOrder("w03_test_owner", "w03_mapping_community");
    }

    @Test
    void explicitFalseAutomaticScanExcludesEnterprise() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("mappingclosed");
        runner(jdbc, "enterprise.foundation.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(entities(context.getBean(EntityManagerFactory.class))).containsExactly(CommunityMappingProbe.class);
        });
        assertThat(jdbc.queryForList("SHOW TABLES", String.class)).containsExactlyInAnyOrder("w03_test_owner", "w03_mapping_community");
    }

    @Test
    void enabledScanMapsExactlyFourTablesAndAllFortyThreeColumnsWithoutDdl() {
        mapped("mappingcolumns", factory -> {
            assertThat(entities(factory)).containsExactlyInAnyOrder(EnterpriseUser.class, EnterpriseTenant.class,
                    EnterpriseTenantMember.class, EnterpriseOrganization.class, CommunityMappingProbe.class);
            var sessionFactory = factory.unwrap(SessionFactoryImplementor.class);
            int count = 0;
            for (var entry : TABLES.entrySet()) {
                var persister = (AbstractEntityPersister) sessionFactory.getMappingMetamodel().getEntityDescriptor(entry.getKey());
                assertThat(persister.getTableName()).isEqualTo(entry.getValue());
                List<String> columns = new ArrayList<>(List.of(persister.getIdentifierColumnNames()));
                for (String property : persister.getPropertyNames()) columns.addAll(List.of(persister.getPropertyColumnNames(property)));
                var expected = FoundationSchema.TABLES.stream().filter(v -> v.name().equals(entry.getValue())).findFirst().orElseThrow();
                assertThat(columns).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected.columns().stream().map(FoundationSchema.Column::name).toList());
                count += columns.size();
            }
            assertThat(count).isEqualTo(43);
        });
    }

    @Test
    void enabledMappingNeverCreatesMissingFoundationTables() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("mappingmissing");
        runner(jdbc, "enterprise.foundation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(entities(context.getBean(EntityManagerFactory.class))).hasSize(5);
        });
        assertThat(jdbc.queryForList("SHOW TABLES", String.class)).containsExactlyInAnyOrder("w03_test_owner", "w03_mapping_community");
        assertThatThrownBy(() -> new FoundationSchemaVerifier(jdbc).run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fourProductionEntitiesRoundTripAssignedIdsAuditEnumsUnicodeAndSchoolCode() {
        mapped("mappingroundtrip", factory -> {
            seed(factory);
            try (var em = factory.createEntityManager()) {
                var user = em.find(EnterpriseUser.class, USER);
                assertThat(user.getId()).isEqualTo(USER);
                assertThat(user.getUsername()).isEqualTo("Synthetic-A");
                assertThat(user.getDisplayName()).isEqualTo("合成用户🙂");
                assertThat(user.getIdentityEpoch()).isEqualTo(1L);
                assertThat(user.getLegacyUid()).isEqualTo(USER + 100);
                assertThat(user.getCreatedBy()).isNull();
                assertThat(user.getUpdatedBy()).isNull();
                var tenant = em.find(EnterpriseTenant.class, TENANT);
                assertThat(tenant.getCode()).isEqualTo("Group-A");
                assertThat(tenant.getName()).isEqualTo("合成集团🙂");
                assertThat(tenant.getAccessEpoch()).isEqualTo(1L);
                var member = em.find(EnterpriseTenantMember.class, MEMBER);
                assertThat(member.getTenantId()).isEqualTo(TENANT);
                assertThat(member.getUserId()).isEqualTo(USER);
                var school = em.find(EnterpriseOrganization.class, SCHOOL);
                assertThat(school.getSchoolCode()).isEqualTo("0007");
                assertThat(school.getKind()).isEqualTo(EnterpriseOrganization.Kind.SCHOOL);
                assertThat(school.getParentId()).isNull();
                assertThat(school.getSchoolId()).isNull();
                for (var record : List.of(user, tenant, member, school)) {
                    assertThat(record.getVersion()).isEqualTo(1L);
                    assertThat(record.getCreatedAt()).isEqualTo(TIME);
                    assertThat(record.getUpdatedAt()).isEqualTo(TIME);
                }
                assertThat(List.of(user.getStatus(), tenant.getStatus(), member.getStatus(), school.getStatus()))
                        .containsOnly(FoundationStatus.DISABLED);
                for (var record : List.of(tenant, member, school)) {
                    assertThat(record.getCreatedBy()).isEqualTo(USER);
                    assertThat(record.getUpdatedBy()).isEqualTo(USER);
                }
            }
        });
    }

    @Test
    void optimisticVersionRejectsStaleUpdatesForAllFourEntities() {
        mapped("mappingversion", factory -> {
            seed(factory);
            Map<Class<? extends FoundationRecord>, Long> targets = Map.of(EnterpriseUser.class, USER,
                    EnterpriseTenant.class, TENANT, EnterpriseTenantMember.class, MEMBER, EnterpriseOrganization.class, SCHOOL);
            for (var target : targets.entrySet()) {
                try (var first = factory.createEntityManager(); var stale = factory.createEntityManager()) {
                    first.getTransaction().begin();
                    stale.getTransaction().begin();
                    var winner = first.find(target.getKey(), target.getValue());
                    var loser = stale.find(target.getKey(), target.getValue());
                    winner.setUpdatedAt(TIME.plusSeconds(1));
                    first.getTransaction().commit();
                    loser.setUpdatedAt(TIME.plusSeconds(2));
                    assertThatThrownBy(stale::flush).isInstanceOf(OptimisticLockException.class);
                    stale.getTransaction().rollback();
                }
                try (var em = factory.createEntityManager()) {
                    var record = em.find(target.getKey(), target.getValue());
                    assertThat(record.getVersion()).isEqualTo(2L);
                    assertThat(record.getUpdatedAt()).isEqualTo(TIME.plusSeconds(1));
                }
            }
        });
    }

    @Test
    void managedExplicitNullClearsOptionalFieldsAndReloadsOutsideContext() {
        mapped("mappingnull", factory -> {
            seed(factory);
            transaction(factory, em -> {
                var department = audit(new EnterpriseOrganization(), SCHOOL + 1);
                department.setTenantId(TENANT);
                department.setKind(EnterpriseOrganization.Kind.DEPARTMENT);
                department.setName("合成机构");
                department.setParentId(SCHOOL);
                department.setSchoolId(SCHOOL);
                em.persist(department);
            });
            transaction(factory, em -> {
                var department = em.find(EnterpriseOrganization.class, SCHOOL + 1);
                department.setParentId(null);
                department.setSchoolId(null);
                department.setUpdatedAt(TIME.plusSeconds(1));
                var user = em.find(EnterpriseUser.class, USER);
                user.setLegacyUid(null);
                user.setUpdatedAt(TIME.plusSeconds(1));
            });
            try (var em = factory.createEntityManager()) {
                assertThat(em.find(EnterpriseUser.class, USER).getLegacyUid()).isNull();
                var department = em.find(EnterpriseOrganization.class, SCHOOL + 1);
                assertThat(department.getParentId()).isNull();
                assertThat(department.getSchoolId()).isNull();
                assertThat(department.getVersion()).isEqualTo(2L);
            }
        });
    }

    @Test
    void multiTableStateAndEpochRollbackTogetherAfterFlush() {
        mapped("mappingrollback", factory -> {
            seed(factory);
            assertThatThrownBy(() -> transaction(factory, em -> {
                em.find(EnterpriseTenantMember.class, MEMBER).setStatus(FoundationStatus.ACTIVE);
                em.find(EnterpriseTenant.class, TENANT).setAccessEpoch(2L);
                em.find(EnterpriseUser.class, USER).setIdentityEpoch(2L);
                em.flush();
                throw new IllegalStateException("synthetic failure after actual SQL");
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("synthetic failure");
            try (var em = factory.createEntityManager()) {
                var member = em.find(EnterpriseTenantMember.class, MEMBER);
                assertThat(member.getStatus()).isEqualTo(FoundationStatus.DISABLED);
                assertThat(member.getVersion()).isEqualTo(1L);
                assertThat(em.find(EnterpriseTenant.class, TENANT).getAccessEpoch()).isEqualTo(1L);
                assertThat(em.find(EnterpriseUser.class, USER).getIdentityEpoch()).isEqualTo(1L);
            }
        });
    }

    @Test
    void crossGroupParentAndSchoolReferencesRejectInBothDirections() {
        mapped("mappingcross", factory -> {
            seed(factory);
            transaction(factory, em -> {
                em.persist(tenant(TENANT + 1, "Group-B"));
                em.flush();
                em.persist(school(SCHOOL + 1, TENANT + 1, "0008"));
            });
            for (boolean reverse : List.of(false, true)) {
                for (boolean parent : List.of(false, true)) {
                    Throwable refusal = catchThrowable(() -> transaction(factory, em -> {
                        var department = audit(new EnterpriseOrganization(), SCHOOL + 2);
                        department.setTenantId(reverse ? TENANT + 1 : TENANT);
                        department.setKind(EnterpriseOrganization.Kind.DEPARTMENT);
                        department.setName("跨集团拒绝夹具");
                        long otherSchool = reverse ? SCHOOL : SCHOOL + 1;
                        assertThat(em.find(EnterpriseOrganization.class, SCHOOL + 2)).isNull();
                        assertThat(em.find(EnterpriseTenant.class, department.getTenantId())).isNotNull();
                        var target = em.find(EnterpriseOrganization.class, otherSchool);
                        assertThat(target).isNotNull();
                        assertThat(target.getKind()).isEqualTo(EnterpriseOrganization.Kind.SCHOOL);
                        assertThat(target.getTenantId()).isEqualTo(reverse ? TENANT : TENANT + 1);
                        assertThat(target.getTenantId()).isNotEqualTo(department.getTenantId());
                        if (parent) department.setParentId(otherSchool); else department.setSchoolId(otherSchool);
                        em.persist(department);
                        em.flush();
                    }));
                    assertForeignKeyFailure(refusal);
                }
            }
            try (var em = factory.createEntityManager()) {
                assertThat(em.find(EnterpriseOrganization.class, SCHOOL + 2)).isNull();
                assertThat(em.find(EnterpriseOrganization.class, SCHOOL)).isNotNull();
                assertThat(em.find(EnterpriseOrganization.class, SCHOOL + 1)).isNotNull();
            }
        });
    }

    @Test
    void disabledSchoolKeepsGloballyReservedSchoolCode() {
        mapped("mappingunique", factory -> {
            seed(factory);
            transaction(factory, em -> em.persist(tenant(TENANT + 1, "Group-B")));
            assertThatThrownBy(() -> transaction(factory, em -> {
                em.persist(school(SCHOOL + 1, TENANT + 1, "0007"));
                em.flush();
            })).isInstanceOf(PersistenceException.class).hasStackTraceContaining("uk_org_school_code");
            try (var em = factory.createEntityManager()) {
                assertThat(em.find(EnterpriseOrganization.class, SCHOOL).getStatus()).isEqualTo(FoundationStatus.DISABLED);
                assertThat(em.find(EnterpriseOrganization.class, SCHOOL + 1)).isNull();
            }
        });
    }

    @Test
    void globalUserCanHaveTwoGroupMembersButNotDuplicateWithinGroup() {
        mapped("mappingmembers", factory -> {
            seed(factory);
            transaction(factory, em -> {
                em.persist(tenant(TENANT + 1, "Group-B"));
                em.flush();
                em.persist(member(MEMBER + 1, TENANT + 1));
            });
            assertThatThrownBy(() -> transaction(factory, em -> {
                em.persist(member(MEMBER + 2, TENANT));
                em.flush();
            })).isInstanceOf(PersistenceException.class).hasStackTraceContaining("uk_member_user");
            try (var em = factory.createEntityManager()) {
                assertThat(em.find(EnterpriseTenantMember.class, MEMBER).getTenantId()).isEqualTo(TENANT);
                assertThat(em.find(EnterpriseTenantMember.class, MEMBER + 1).getTenantId()).isEqualTo(TENANT + 1);
                assertThat(em.find(EnterpriseTenantMember.class, MEMBER + 2)).isNull();
            }
        });
    }

    @Test
    void immutableOwnershipAndNaturalKeysRemainUnchangedDuringOrdinaryJpaUpdate() {
        mapped("mappingimmutable", factory -> {
            seed(factory);
            transaction(factory, em -> {
                em.find(EnterpriseUser.class, USER).setUsername("replacement");
                em.find(EnterpriseTenant.class, TENANT).setCode("replacement");
                var member = em.find(EnterpriseTenantMember.class, MEMBER);
                member.setTenantId(TENANT + 999);
                member.setUserId(USER + 999);
                var school = em.find(EnterpriseOrganization.class, SCHOOL);
                school.setTenantId(TENANT + 999);
                school.setSchoolCode("replacement");
                school.setKind(EnterpriseOrganization.Kind.DEPARTMENT);
                school.setName("修改展示名称");
            });
            try (var em = factory.createEntityManager()) {
                assertThat(em.find(EnterpriseUser.class, USER).getUsername()).isEqualTo("Synthetic-A");
                assertThat(em.find(EnterpriseTenant.class, TENANT).getCode()).isEqualTo("Group-A");
                assertThat(em.find(EnterpriseTenantMember.class, MEMBER).getTenantId()).isEqualTo(TENANT);
                assertThat(em.find(EnterpriseTenantMember.class, MEMBER).getUserId()).isEqualTo(USER);
                var school = em.find(EnterpriseOrganization.class, SCHOOL);
                assertThat(school.getTenantId()).isEqualTo(TENANT);
                assertThat(school.getSchoolCode()).isEqualTo("0007");
                assertThat(school.getKind()).isEqualTo(EnterpriseOrganization.Kind.SCHOOL);
                assertThat(school.getName()).isEqualTo("修改展示名称");
            }
        });
    }
}
