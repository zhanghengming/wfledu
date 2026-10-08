package io.dataease.enterprise.foundation;

import io.dataease.config.JpaUpdateNonNullAspect;
import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.extensions.datasource.utils.SpringContextUtil;
import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import io.dataease.listener.InitSqlListener;
import io.dataease.migrationfixture.W03VersionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/** Production listener, entity, repository and merge aspect against retained synthetic MySQL 8 schemas. */
class EnterpriseMigrationEvolutionTest {
    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = W03VersionRepository.class)
    @EnableAspectJAutoProxy
    @Import(JpaUpdateNonNullAspect.class)
    static class RepositoryConfiguration { }

    @BeforeAll
    static void guardDatabase() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }

    private void fixture(String scenario, List<SqlBlock> extra, Consumer<Fixture> check) {
        var jdbc = FoundationMigrationTest.fresh(scenario);
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class, RepositoryConfiguration.class)
                .withBean(DataSource.class, jdbc::getDataSource)
                .withBean(PersistenceManagedTypes.class, () -> PersistenceManagedTypes.of(DeStandaloneVersion.class.getName()))
                .withBean("foundation", EnterpriseFoundationSqlBlock.class, () -> new EnterpriseFoundationSqlBlock(jdbc))
                .withPropertyValues("enterprise.foundation.enabled=true", "spring.jpa.hibernate.ddl-auto=update");
        for (int i = 0; i < extra.size(); i++) {
            SqlBlock block = extra.get(i);
            runner = runner.withBean("fixtureBlock" + i, SqlBlock.class, () -> block);
        }
        var previous = SpringContextUtil.getApplicationContext();
        try {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                new SpringContextUtil().setApplicationContext(context.getSourceApplicationContext());
                check.accept(new Fixture(jdbc, context.getBean(W03VersionRepository.class),
                        (GenericApplicationContext) context.getSourceApplicationContext()));
            });
        } finally { new SpringContextUtil().setApplicationContext(previous); }
    }

    private record Fixture(JdbcTemplate jdbc, W03VersionRepository versions, GenericApplicationContext context) {
        void run() {
            var listener = new InitSqlListener();
            ReflectionTestUtils.setField(listener, "deStandaloneVersionRepository", versions);
            listener.run(null);
        }
        long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
        void record(int rank, String version, boolean success) { versions.saveAndFlush(history(rank, version, success)); }
        void add(SqlBlock block) { context.registerBean("syntheticEvolution", SqlBlock.class, () -> block); }
    }

    private static DeStandaloneVersion history(int rank, String version, Boolean success) {
        var row = new DeStandaloneVersion();
        row.setId(rank); row.setVersion(version); row.setSuccess(success);
        row.setDescription("synthetic evolution"); row.setType("SQL"); row.setScript("synthetic-only");
        row.setInstalledBy("synthetic"); row.setInstalledOn(LocalDateTime.parse("2026-10-08T04:00:00"));
        row.setExecutionTime(0); row.setChecksum(0);
        return row;
    }

    private static SqlBlock block(String version, String group, Runnable action) {
        return new SqlBlock() {
            @Override public Version getVersion() { return new Version(version); }
            @Override public String getVersionGroup() { return group; }
            @Override public void execute() { action.run(); }
        };
    }

    @Test
    void prefixSimilarVersionGroupCannotSuppressFoundationMigration() {
        fixture("evolutionprefix", List.of(), f -> {
            f.record(1, "40.1", true);
            f.run();
            assertThat(f.count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'de_ent_%'")).isEqualTo(4);
            assertThat(f.versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.1", "40.1");
        });
    }

    @Test
    void unsupportedNewerHistoryRejectsBeforeDdlVersionWritesAndCommunityBlocks() {
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        fixture("evolutionfuture", List.of(block("1.999", "1", () -> ran.set(true))), f -> {
            f.record(1, "4.1", true);
            f.record(2, "4.2", true);
            assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
            assertThat(ran).isFalse();
            assertThat(f.versions.findRecords()).hasSize(2);
            assertThat(f.count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'de_ent_%'")).isZero();
        });
    }

    private static List<SqlBlock> plan() {
        return List.of(block("4.1", "4", () -> { }), block("4.2", "4", () -> { }));
    }

    private static List<FoundationSchema.Table> evolvedTarget() {
        var result = new ArrayList<>(FoundationSchemaV41.TABLES);
        var user = result.getFirst();
        var columns = new ArrayList<>(user.columns());
        columns.add(new FoundationSchema.Column("w03_evolution_probe", "bigint", true, null, "合成演进探针"));
        result.set(0, new FoundationSchema.Table(user.name(), user.comment(), columns, user.keys(), user.foreignKeys(), user.checks()));
        result.add(new FoundationSchema.Table("de_ent_w03_evolution_probe", "合成演进探针，仅测试",
                List.of(new FoundationSchema.Column("id", "bigint", false, null, "合成编号"),
                        new FoundationSchema.Column("payload", "varchar(64)", false, null, "合成内容")),
                List.of(new FoundationSchema.Key("PRIMARY", true, List.of("id"))), List.of(), Map.of()));
        return result;
    }

    /** Test-only 4.2. Never a production component, formal migration, or dictionary declaration. */
    private static final class SyntheticEvolution implements SqlBlock {
        final JdbcTemplate jdbc;
        boolean interrupt;
        int calls;
        SyntheticEvolution(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public Version getVersion() { return new Version("4.2"); }
        @Override public String getVersionGroup() { return "4"; }
        @Override public void execute() {
            calls++;
            var target = evolvedTarget();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_NAME='de_ent_user' AND COLUMN_NAME='w03_evolution_probe'", Integer.class) == 0) {
                jdbc.execute("ALTER TABLE de_ent_user ADD COLUMN w03_evolution_probe BIGINT NULL COMMENT '合成演进探针'");
            }
            FoundationSchema.verify(jdbc, target.getFirst());
            if (interrupt) throw new IllegalStateException("Synthetic interruption after committed ALTER");
            var probe = target.getLast();
            if (!FoundationSchema.exists(jdbc, probe)) jdbc.execute(probe.ddl());
            FoundationSchema.verify(jdbc, probe);
        }
    }

    @Test
    void frozenV41DdlHashesAndNestedDescriptorsAreImmutable() throws Exception {
        // These fingerprints were obtained from the preceding delivery's compiled class before this refactor.
        var expected = Map.of("de_ent_user", "02924549b95dbfdfa7c7ebc07883b0cd5275289e8faa87db3e44773c2e2df9f9",
                "de_ent_tenant", "61000f6a8fa9be13c6f996b9fe1c6b3d14c37d22905cb9d5bb5b9ac6ab0acc57",
                "de_ent_tenant_member", "fa705d87b27e2c1930308ce6526e0b2d1971edd4e83026d70cf4afd9355354a9",
                "de_ent_org", "29d051269cbc5751f3bdae0620b93c0ba967b8e798b366e63147f796e78d19e4");
        for (var table : FoundationSchemaV41.TABLES) {
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(table.ddl().getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(expected.get(table.name()));
            assertThatThrownBy(() -> table.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> table.checks().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> table.keys().getFirst().columns().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> table.foreignKeys().getFirst().targetColumns().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThatThrownBy(() -> FoundationSchemaV41.TABLES.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateNonCanonicalWrongGroupAndGappedPlansReject() {
        for (var invalid : List.of(List.of(block("4.2", "4", () -> { })),
                List.of(block("4.1", "4", () -> { }), block("4.3", "4", () -> { })),
                List.of(block("4.1", "4", () -> { }), block("4.1", "4", () -> { })),
                List.of(block("4.01", "4", () -> { })), List.of(block("4.1.0", "4", () -> { })),
                List.of(block("4.x", "4", () -> { })), List.of(block("4.1000000000", "4", () -> { })),
                List.of(block("4.1", "40", () -> { })))) {
            assertThatThrownBy(() -> EnterpriseMigrationHistory.validate(invalid, List.of())).isInstanceOf(IllegalStateException.class);
        }
        EnterpriseMigrationHistory.validate(plan(), List.of());
    }

    @Test
    void unknownSkippedRegressedDuplicateAndIncompleteHistoriesReject() {
        for (var invalid : List.of(List.of(history(1, "4.99", true)), List.of(history(1, "4.2", true)),
                List.of(history(1, "4.1", true), history(2, "4.1", true)),
                List.of(history(1, "4.1", false), history(2, "4.2", true)),
                List.of(history(1, "4.1", true), history(2, "4.2", true), history(3, "4.1", false)),
                List.of(history(1, "4.1", null)), List.of(history(0, "4.1", false)),
                List.of(history(1, "4.1.0", true)), List.of(history(1, "4", true)),
                List.of(history(1, "4.1", false), history(1, "4.1", true)))) {
            assertThatThrownBy(() -> EnterpriseMigrationHistory.validate(plan(), invalid)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void repeatedFailuresThenSuccessAndNextFailureRemainRetryable() {
        var rows = List.of(history(4, "4.2", false), history(2, "4.1", false),
                history(3, "4.1", true), history(1, "4.1", false));
        EnterpriseMigrationHistory.validate(plan(), rows);
        assertThat(rows).extracting(DeStandaloneVersion::getId).containsExactly(4, 2, 3, 1);
        assertThat(rows).extracting(DeStandaloneVersion::getSuccess).containsExactly(false, false, true, false);
    }

    @Test
    void otherGroupsKeepTheirExistingHistorySemantics() {
        EnterpriseMigrationHistory.validate(plan(), List.of(history(0, "40.1", null), history(1, "1.2.0", null), history(2, "3.0", false)));
        assertThat(EnterpriseMigrationHistory.ownsVersion("40.1")).isFalse();
        assertThat(EnterpriseMigrationHistory.ownsVersion("4.1")).isTrue();
    }

    @Test
    void currentTargetSnapshotsCannotMutateFrozenHistory() {
        var original = FoundationSchemaV41.TABLES.getFirst();
        var columns = new ArrayList<>(original.columns());
        var checks = new LinkedHashMap<>(original.checks());
        var copy = new FoundationSchema.Table(original.name(), original.comment(), columns, original.keys(), original.foreignKeys(), checks);
        columns.clear(); checks.clear();
        assertThat(copy.columns()).isEqualTo(original.columns());
        assertThat(copy.checks()).isEqualTo(original.checks());
        var current = evolvedTarget();
        assertThat(current.getFirst().columns()).hasSize(original.columns().size() + 1);
        assertThat(original.columns()).extracting(FoundationSchema.Column::name).doesNotContain("w03_evolution_probe");
        assertThat(FoundationSchema.TABLES).hasSize(4);
        assertThatThrownBy(() -> new FoundationSchemaVerifier(new JdbcTemplate(), List.of(original, original)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realListenerEmptyUpgradeAndRepeatedStartupKeepCurrentTargetAndRows() {
        fixture("evolutionupgrade", List.of(), f -> {
            f.run();
            f.jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'synthetic-retain','合成保留')");
            var upgrade = new SyntheticEvolution(f.jdbc);
            f.add(upgrade); f.run();
            var target = evolvedTarget();
            var verifier = new FoundationSchemaVerifier(f.jdbc, target);
            target.clear(); // Verifier owns its snapshot.
            verifier.run(null);
            assertThatThrownBy(() -> new EnterpriseFoundationSqlBlock(f.jdbc).verifySchema()).isInstanceOf(IllegalStateException.class);
            f.run(); verifier.run(null);
            assertThat(upgrade.calls).isEqualTo(1);
            assertThat(f.versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.2", "4.1");
            assertThat(f.jdbc.queryForObject("SELECT display_name FROM de_ent_user WHERE id=1", String.class)).isEqualTo("合成保留");
            assertThat(f.count("SELECT COUNT(*) FROM de_ent_user")).isEqualTo(1);
        });
    }

    @Test
    void partialSecondMigrationRetainsCommittedDdlAndRetriesWithoutV41() {
        fixture("evolutionretry", List.of(), f -> {
            f.run();
            f.jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'synthetic-retry','合成保留')");
            var upgrade = new SyntheticEvolution(f.jdbc);
            upgrade.interrupt = true; f.add(upgrade);
            assertThatThrownBy(f::run).isInstanceOf(RuntimeException.class).hasMessageContaining("4.2");
            assertThat(f.count("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='w03_evolution_probe'")).isEqualTo(1);
            assertThat(f.versions.findRecords().getFirst().getSuccess()).isFalse();
            assertThat(f.count("SELECT COUNT(*) FROM de_ent_user")).isEqualTo(1);
            upgrade.interrupt = false; f.run();
            new FoundationSchemaVerifier(f.jdbc, evolvedTarget()).run(null);
            f.run();
            assertThat(upgrade.calls).isEqualTo(2);
            assertThat(f.versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsExactly(true, false, true);
            assertThat(f.versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.2", "4.2", "4.1");
        });
    }

    @Test
    void currentVerifierRejectsEvolvedDriftWithoutChangingHistoryOrRows() {
        fixture("evolutiondrift", List.of(), f -> {
            f.run(); var upgrade = new SyntheticEvolution(f.jdbc); f.add(upgrade); f.run();
            f.jdbc.update("INSERT INTO de_ent_w03_evolution_probe VALUES(1,'retain')");
            f.jdbc.execute("ALTER TABLE de_ent_user MODIFY COLUMN w03_evolution_probe BIGINT NULL COMMENT '错误注释'");
            var ddl = f.jdbc.queryForMap("SHOW CREATE TABLE de_ent_user").get("Create Table");
            f.run(); // Successful history is not rerun to repair drift.
            assertThatThrownBy(() -> new FoundationSchemaVerifier(f.jdbc, evolvedTarget()).run(null)).isInstanceOf(IllegalStateException.class);
            assertThat(f.jdbc.queryForMap("SHOW CREATE TABLE de_ent_user").get("Create Table")).isEqualTo(ddl);
            assertThat(f.versions.findRecords()).hasSize(2);
            assertThat(f.jdbc.queryForObject("SELECT payload FROM de_ent_w03_evolution_probe WHERE id=1", String.class)).isEqualTo("retain");
        });
        fixture("evolutionundeclared", List.of(), f -> {
            f.run(); var upgrade = new SyntheticEvolution(f.jdbc); f.add(upgrade); f.run();
            f.jdbc.execute("CREATE TABLE deXentX_probe(id BIGINT) COMMENT='合成非保留表'");
            new FoundationSchemaVerifier(f.jdbc, evolvedTarget()).run(null);
            f.jdbc.execute("CREATE TABLE de_ent_w03_undeclared_probe(id BIGINT) COMMENT='合成未知保留表'");
            var before = f.jdbc.queryForMap("SHOW CREATE TABLE de_ent_w03_undeclared_probe").get("Create Table");
            assertThatThrownBy(() -> new FoundationSchemaVerifier(f.jdbc, evolvedTarget()).run(null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("undeclared");
            assertThat(f.jdbc.queryForMap("SHOW CREATE TABLE de_ent_w03_undeclared_probe").get("Create Table")).isEqualTo(before);
            assertThat(f.versions.findRecords()).hasSize(2);
        });
    }

    @Test
    void invalidPlansAndUnresolvedFailuresPreventActualVersionWrites() {
        for (String version : List.of("4.1", "4.3", "4.01")) {
            fixture("evolutioninvalid", List.of(block(version, "4", () -> fail("Invalid migration executed"))), f -> {
                assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
                assertThat(f.versions.findRecords()).isEmpty();
                assertThat(f.count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'de_ent_%'")).isZero();
            });
        }
        fixture("evolutionhistory", List.of(block("4.2", "4", () -> fail("Invalid history executed"))), f -> {
            f.record(1, "4.1", false); f.record(2, "4.2", true);
            assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
            assertThat(f.versions.findRecords()).hasSize(2);
        });
        fixture("evolutionrank", List.of(), f -> {
            f.record(Integer.MAX_VALUE, "4.1", false);
            assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
            assertThat(f.versions.findRecords()).hasSize(1);
            assertThat(f.count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'de_ent_%'")).isZero();
        });
    }
}
