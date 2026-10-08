package io.dataease.enterprise.foundation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.hibernate.cfg.SchemaToolingSettings.HBM2DDL_FILTER_PROVIDER;

/** Real Boot/Hibernate startup and MySQL 8; only retained synthetic W03 schemas. */
class EnterpriseJpaIsolationTest {
    @BeforeAll
    static void guardDatabase() throws Exception {
        FoundationMigrationTest.guardedPrivateConnection();
    }

    private ApplicationContextRunner runner(JdbcTemplate jdbc, boolean protectedSchema, boolean foundation,
                                            Class<?>... mappings) {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withBean(DataSource.class, jdbc::getDataSource)
                .withBean(PersistenceManagedTypes.class, () -> PersistenceManagedTypes.of(
                        java.util.Arrays.stream(mappings).map(Class::getName).toArray(String[]::new)))
                .withPropertyValues("spring.jpa.hibernate.ddl-auto=update", "spring.jpa.open-in-view=false",
                        "enterprise.foundation.enabled=" + foundation);
        return protectedSchema ? runner.withUserConfiguration(EnterpriseJpaConfiguration.class) : runner;
    }

    private static int tables(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                Integer.class, table);
    }

    private static org.hibernate.mapping.Table mappedTable(String name) {
        var table = new org.hibernate.mapping.Table();
        table.setName(name);
        return table;
    }

    private static Map<String, Object> structure(JdbcTemplate jdbc) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var table : FoundationSchema.TABLES) {
            result.put(table.name(), jdbc.queryForMap("SHOW CREATE TABLE `" + table.name() + "`").get("Create Table"));
        }
        return result;
    }

    private static void rejectedBeforeDdl(ApplicationContextRunner runner, JdbcTemplate jdbc, String reason) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Enterprise JPA mapping rejected: " + reason);
        });
        assertThat(jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()",
                String.class)).containsExactly("w03_test_owner");
    }

    @Test
    void schemaOwnershipRegisteredEvenWhenFoundationSwitchIsAbsent() {
        try (var context = new AnnotationConfigApplicationContext(EnterpriseJpaConfiguration.class)) {
            var properties = new LinkedHashMap<String, Object>();
            context.getBean(HibernatePropertiesCustomizer.class).customize(properties);
            assertThat(properties.get(HBM2DDL_FILTER_PROVIDER)).isInstanceOf(EnterpriseSchemaFilterProvider.class);
            assertThat(properties.get("hibernate.integrator_provider")).isNotNull();
        }
    }

    @Test
    void existingProvidersAreRejectedWithoutOverwritingThem() {
        try (var context = new AnnotationConfigApplicationContext(EnterpriseJpaConfiguration.class)) {
            var customizer = context.getBean(HibernatePropertiesCustomizer.class);
            for (String key : List.of(HBM2DDL_FILTER_PROVIDER, "hibernate.integrator_provider")) {
                var properties = new LinkedHashMap<String, Object>();
                Object original = new Object();
                properties.put(key, original);
                assertThatThrownBy(() -> customizer.customize(properties)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("conflicts with an existing provider");
                assertThat(properties).hasSize(1).containsEntry(key, original);
            }
        }
    }

    @Test
    void allSchemaOperationsExcludeReservedTableNames() {
        var provider = new EnterpriseSchemaFilterProvider();
        for (var filter : List.of(provider.getCreateFilter(), provider.getDropFilter(), provider.getMigrateFilter(),
                provider.getValidateFilter(), provider.getTruncatorFilter())) {
            for (String name : List.of("de_ent_user", "de_ent_future_table", "`DE_ENT_quoted`")) {
                assertThat(filter.includeTable(mappedTable(name))).isFalse();
            }
            assertThat(filter.includeTable(mappedTable("core_datasource"))).isTrue();
            assertThat(filter.includeTable(mappedTable("de_entity_other"))).isTrue();
        }
    }

    @Test
    void unprotectedHibernateActuallyCreatesEnterpriseTable() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpaoracle");
        runner(jdbc, false, true, MissingEnterprise.class, CommunityProbe.class).run(context -> assertThat(context).hasNotFailed());
        assertThat(tables(jdbc, "de_ent_missing_probe")).isEqualTo(1);
        assertThat(tables(jdbc, "w03_community_probe")).isEqualTo(1);
    }

    @Test
    void missingEnterpriseTableStaysAbsentWhileCommunityTableIsCreated() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpamissing");
        runner(jdbc, true, true, MissingEnterprise.class, CommunityProbe.class).run(context -> assertThat(context).hasNotFailed());
        assertThat(tables(jdbc, "de_ent_missing_probe")).isZero();
        assertThat(tables(jdbc, "w03_community_probe")).isEqualTo(1);
        assertThatThrownBy(() -> new FoundationSchemaVerifier(jdbc).run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tables(jdbc, "de_ent_user")).isZero();
    }

    @Test
    void existingEnterpriseStructureAndRowsSurviveCommunityColumnUpdate() {
        JdbcTemplate jdbc = FoundationMigrationTest.migrated("jpaexisting");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name) VALUES (10,'jpa-A','合成集团A')");
        jdbc.execute("CREATE TABLE w03_community_probe (id bigint NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        var before = structure(jdbc);
        var rows = jdbc.queryForList("SELECT * FROM de_ent_tenant");
        runner(jdbc, true, true, IncompatibleTenant.class, CommunityProbe.class).run(context -> assertThat(context).hasNotFailed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='w03_community_probe' AND COLUMN_NAME='label'", Integer.class)).isEqualTo(1);
        assertThat(structure(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM de_ent_tenant")).isEqualTo(rows);
        FoundationSchema.TABLES.forEach(table -> FoundationSchema.verify(jdbc, table));
    }

    @Test
    void driftRemainsUnrepairedAndStrictVerifierRejectsIt() {
        JdbcTemplate jdbc = FoundationMigrationTest.migrated("jpadrift");
        jdbc.execute("ALTER TABLE de_ent_tenant MODIFY COLUMN name varchar(64) NOT NULL COMMENT '集团展示名称'");
        var before = structure(jdbc);
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_tenant' AND COLUMN_NAME='name'", String.class)).isEqualTo("varchar(64)");
        runner(jdbc, true, true, TenantRead.class, CommunityProbe.class).run(context -> assertThat(context).hasNotFailed());
        assertThat(structure(jdbc)).isEqualTo(before);
        assertThatThrownBy(() -> new FoundationSchemaVerifier(jdbc).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
        assertThat(structure(jdbc)).isEqualTo(before);
    }

    @Test
    void enterpriseMappingsRequireFoundationBeforeAnyDdl() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpaswitch");
        rejectedBeforeDdl(runner(jdbc, true, false, MissingEnterprise.class, CommunityProbe.class), jdbc,
                "enterprise mappings require enterprise.foundation.enabled=true");
    }

    @Test
    void crossBoundaryForeignKeyIsRejectedBeforeAnyDdl() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpaforeign");
        rejectedBeforeDdl(runner(jdbc, true, true, TenantRead.class, CommunityReference.class), jdbc,
                "foreign keys cannot cross the enterprise schema boundary");
    }

    @Test
    void mixedSecondaryTableIsRejectedBeforeAnyDdl() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpasecondary");
        rejectedBeforeDdl(runner(jdbc, true, true, MixedSecondary.class), jdbc,
                "secondary tables cannot cross the enterprise schema boundary");
    }

    @Test
    void generatedEnterpriseIdIsRejectedBeforeAnyDdl() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpagenerator");
        rejectedBeforeDdl(runner(jdbc, true, true, GeneratedEnterprise.class, CommunityProbe.class), jdbc,
                "enterprise identifiers must be assigned by the application");
    }

    @Test
    void explicitEnterpriseCatalogIsRejectedBeforeAnyDdl() {
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpacatalog");
        rejectedBeforeDdl(runner(jdbc, true, true, ExplicitCatalog.class, CommunityProbe.class), jdbc,
                "enterprise mappings cannot select another catalog or schema");
    }

    @Test
    void replacedSchemaFilterIsRejectedBeforeAnyDdl() {
        var expected = new EnterpriseSchemaFilterProvider();
        var factory = org.mockito.Mockito.mock(org.hibernate.engine.spi.SessionFactoryImplementor.class);
        var metadata = org.mockito.Mockito.mock(org.hibernate.boot.Metadata.class);
        org.mockito.Mockito.when(factory.getProperties()).thenReturn(Map.of(HBM2DDL_FILTER_PROVIDER,
                new EnterpriseSchemaFilterProvider()));
        assertThatThrownBy(() -> new EnterpriseMappingGuard(expected, true).integrate(metadata, null, factory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema filter was replaced");
        org.mockito.Mockito.verifyNoInteractions(metadata);
        JdbcTemplate jdbc = FoundationMigrationTest.fresh("jpareplaced");
        var runner = runner(jdbc, true, true, MissingEnterprise.class, CommunityProbe.class)
                .withPropertyValues("spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider=org.hibernate.tool.schema.internal.DefaultSchemaFilterProvider");
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("conflicts with an existing provider");
        });
        assertThat(tables(jdbc, "de_ent_missing_probe")).isZero();
        assertThat(tables(jdbc, "w03_community_probe")).isZero();
    }

    @Test
    void migratedTableSupportsJpaReadAndTransactionalUpdate() {
        JdbcTemplate jdbc = FoundationMigrationTest.migrated("jpadml");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name) VALUES (10,'jpa-A','合成集团A')");
        var before = structure(jdbc);
        runner(jdbc, true, true, TenantRead.class).run(context -> {
            assertThat(context).hasNotFailed();
            var emf = context.getBean(EntityManagerFactory.class);
            try (var manager = emf.createEntityManager()) {
                manager.getTransaction().begin();
                TenantRead entity = manager.find(TenantRead.class, 10L);
                assertThat(entity.name).isEqualTo("合成集团A");
                entity.name = "JPA合成更新";
                manager.getTransaction().commit();
            }
        });
        assertThat(jdbc.queryForObject("SELECT name FROM de_ent_tenant WHERE id=10", String.class)).isEqualTo("JPA合成更新");
        assertThat(structure(jdbc)).isEqualTo(before);
        FoundationSchema.TABLES.forEach(table -> FoundationSchema.verify(jdbc, table));
    }

    @Entity(name = "MissingEnterprise")
    @Table(name = "de_ent_missing_probe")
    public static class MissingEnterprise {
        @Id public Long id;
    }

    @Entity(name = "CommunityProbe")
    @Table(name = "w03_community_probe")
    public static class CommunityProbe {
        @Id public Long id;
        public String label;
    }

    @Entity(name = "IncompatibleTenant")
    @Table(name = "de_ent_tenant")
    public static class IncompatibleTenant {
        @Id public Long id;
        @Column(length = 256) public String name;
        @Column(name = "hibernate_only") public String hibernateOnly;
    }

    @Entity(name = "TenantRead")
    @Table(name = "de_ent_tenant")
    public static class TenantRead {
        @Id public Long id;
        @Column(length = 128) public String name;
    }

    @Entity(name = "CommunityReference")
    @Table(name = "w03_community_reference")
    public static class CommunityReference {
        @Id public Long id;
        @ManyToOne @JoinColumn(name = "tenant_id") public TenantRead tenant;
    }

    @Entity(name = "MixedSecondary")
    @Table(name = "de_ent_mixed_probe")
    @SecondaryTable(name = "w03_secondary_probe")
    public static class MixedSecondary {
        @Id public Long id;
        @Column(table = "w03_secondary_probe") public String label;
    }

    @Entity(name = "GeneratedEnterprise")
    @Table(name = "de_ent_generated_probe")
    public static class GeneratedEnterprise {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    }

    @Entity(name = "ExplicitCatalog")
    @Table(name = "de_ent_explicit_probe", catalog = "untrusted_catalog")
    public static class ExplicitCatalog {
        @Id public Long id;
    }
}
