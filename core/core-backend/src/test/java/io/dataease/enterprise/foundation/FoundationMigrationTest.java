package io.dataease.enterprise.foundation;

import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.dao.auto.repo.DeStandaloneVersionRepository;
import io.dataease.extensions.datasource.utils.SpringContextUtil;
import io.dataease.listener.InitSqlListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Requires the authorized isolated server; never skips or substitutes H2 for MySQL. */
class FoundationMigrationTest {
    private static final Path ROOT = Path.of("/home/data_dev_zhm/dataease-phase1-test/w02-security");
    private static String user;
    private static String password;

    @BeforeAll
    static void guardedPrivateConnection() throws Exception {
        assertThat(Path.of("").toAbsolutePath().normalize()).isEqualTo(ROOT.resolve("source/core/core-backend"));
        Properties config = new Properties();
        try (var input = Files.newInputStream(ROOT.resolve("runtime/conf/w03-client.cnf"))) {
            config.load(input);
        }
        assertThat(config.getProperty("host")).isEqualTo("127.0.0.1");
        assertThat(config.getProperty("port")).isEqualTo("13306");
        assertThat(config.getProperty("protocol")).isEqualTo("tcp");
        assertThat(config.getProperty("user")).isEqualTo("de_phase1_w03_test");
        user = config.getProperty("user");
        password = config.getProperty("password");
        assertThat(user).isNotBlank();
        assertThat(password).isNotBlank();
        JdbcTemplate jdbc = connect("");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.");
    }

    private static JdbcTemplate connect(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mariadb://127.0.0.1:13306/" + database + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&serverRsaPublicKeyFile=/home/data_dev_zhm/dataease-phase1-test/w02-security/runtime/mysql/data/public_key.pem", user, password));
    }

    private static JdbcTemplate fresh(String scenario) {
        String database = "de_phase1_w03_" + scenario + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        if (!database.matches("de_phase1_w03_[a-z]+_[a-f0-9]{12}")) throw new IllegalStateException("Unsafe test database");
        connect("").execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        JdbcTemplate jdbc = connect(database);
        jdbc.execute("CREATE TABLE w03_test_owner (marker varchar(64) NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO w03_test_owner VALUES (?)", "synthetic-only-retain-no-drop");
        System.out.println("W03 retained synthetic database: " + database);
        return jdbc;
    }

    private static JdbcTemplate migrated(String scenario) {
        JdbcTemplate jdbc = fresh(scenario);
        new EnterpriseFoundationSqlBlock(jdbc).execute();
        return jdbc;
    }

    private static void principals(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES (1,'synthetic-user','合成用户')");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name) VALUES (10,'synthetic-A','合成集团A'),(20,'synthetic-B','合成集团B')");
    }

    private static void school(JdbcTemplate jdbc, long id, long tenant, String code) {
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,school_code) VALUES (?,?,'SCHOOL','合成学校',?)", id, tenant, code);
    }

    @Test
    void emptySchemaHasExactFieldsCommentsIndicesChecksAndForeignKeys() {
        JdbcTemplate jdbc = migrated("empty");
        FoundationSchema.TABLES.forEach(table -> FoundationSchema.verify(jdbc, table));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'de_ent_%'", Integer.class)).isEqualTo(4);
        for (var table : FoundationSchema.TABLES) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `" + table.name() + "`", Integer.class)).isZero();
        }
    }

    @Test
    void existingLegacyDataAndRepeatedDdlArePreserved() {
        JdbcTemplate jdbc = fresh("history");
        jdbc.execute("CREATE TABLE legacy_synthetic (id bigint PRIMARY KEY, payload varchar(64))");
        jdbc.update("INSERT INTO legacy_synthetic VALUES (7,'unmapped-retain')");
        new EnterpriseFoundationSqlBlock(jdbc).execute();
        principals(jdbc);
        new EnterpriseFoundationSqlBlock(jdbc).execute();
        assertThat(jdbc.queryForObject("SELECT payload FROM legacy_synthetic WHERE id=7", String.class)).isEqualTo("unmapped-retain");
        assertThat(jdbc.queryForObject("SELECT status FROM de_ent_tenant WHERE id=10", String.class)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT legacy_uid FROM de_ent_user WHERE id=1", Long.class)).isNull();
    }

    @Test
    void oneGlobalUserCanJoinTwoIndependentGroupsWithoutDuplicateMembership() {
        JdbcTemplate jdbc = migrated("member");
        principals(jdbc);
        jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id) VALUES(101,10,1),(102,20,1)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_tenant_member WHERE user_id=1 AND status='DISABLED'", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id) VALUES(103,10,1)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id) VALUES(104,999,1)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
    }

    @Test
    void schoolCodeIsGloballyReservedIncludingDisabledSchoolsAndBothDirections() {
        JdbcTemplate jdbc = migrated("unique");
        principals(jdbc);
        school(jdbc, 101, 10, "001");
        school(jdbc, 201, 20, "002");
        assertThatThrownBy(() -> school(jdbc, 202, 20, "001")).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        assertThatThrownBy(() -> school(jdbc, 102, 10, "002")).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        school(jdbc, 103, 10, "SchoolA");
        school(jdbc, 104, 10, "schoola");
        school(jdbc, 105, 10, "1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_org WHERE kind='SCHOOL'", Integer.class)).isEqualTo(5);
    }

    @Test
    void parentAndSchoolForeignKeysRejectBothCrossGroupDirections() {
        JdbcTemplate jdbc = migrated("cross");
        principals(jdbc);
        school(jdbc, 101, 10, "001");
        school(jdbc, 201, 20, "002");
        for (long[] ids : List.of(new long[]{10, 201}, new long[]{20, 101})) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_org(id,tenant_id,parent_id,kind,name) VALUES(301,?,?,'DEPARTMENT','合成部门')", ids[0], ids[1]))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_org(id,tenant_id,school_id,kind,name) VALUES(302,?,?,'DEPARTMENT','合成部门')", ids[0], ids[1]))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        }
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,parent_id,school_id,kind,name) VALUES(303,10,101,101,'DEPARTMENT','合成财务')");
    }

    @Test
    void invalidSchoolsUnknownGroupsAndMalformedKindsAreRejected() {
        JdbcTemplate jdbc = migrated("invalid");
        principals(jdbc);
        for (String code : List.of("", " 001", "001 ")) {
            assertThatThrownBy(() -> school(jdbc, 101, 10, code)).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        }
        assertThatThrownBy(() -> school(jdbc, 101, 999, "001")).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        for (String kind : List.of("SCHOOL", "DEPARTMENT", "UNKNOWN")) {
            String code = kind.equals("DEPARTMENT") ? "001" : null;
            assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,school_code) VALUES(101,10,?,'合成组织',?)", kind, code))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_org(id,tenant_id,parent_id,kind,name) VALUES(101,10,101,'DEPARTMENT','合成组织')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
    }

    @Test
    void positiveEpochsStatusAndReviewedLegacyMappingAreEnforced() {
        JdbcTemplate jdbc = migrated("epochs");
        principals(jdbc);
        for (String update : List.of("identity_epoch=0", "version=0", "legacy_uid=0", "status='active'", "username=' synthetic'", "status=NULL")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE de_ent_user SET " + update + " WHERE id=1"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE de_ent_tenant SET access_epoch=0 WHERE id=10"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(0,'bad-id','合成用户')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
    }

    @Test
    void auditReferencesRestrictDeletionAndRejectUnknownActors() {
        JdbcTemplate jdbc = migrated("audit");
        principals(jdbc);
        jdbc.update("UPDATE de_ent_tenant SET created_by=1,updated_by=1 WHERE id=10");
        school(jdbc, 100, 10, "001");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM de_ent_user WHERE id=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        assertThatThrownBy(() -> jdbc.update("UPDATE de_ent_org SET created_by=999 WHERE id=100"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
    }

    @Test
    void incompatibleExistingTableFailsBeforeCreatingOtherTables() {
        JdbcTemplate jdbc = fresh("drift");
        jdbc.execute(FoundationSchema.TABLES.getFirst().ddl().replace("varchar(128)", "varchar(127)"));
        assertThatThrownBy(() -> new EnterpriseFoundationSqlBlock(jdbc).execute())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_tenant'", Integer.class)).isZero();
    }

    @Test
    void disabledCheckConstraintCannotBeAcceptedAsValidSchema() {
        JdbcTemplate jdbc = migrated("checks");
        jdbc.execute("ALTER TABLE de_ent_user ALTER CHECK ck_user_epoch NOT ENFORCED");
        assertThatThrownBy(() -> new EnterpriseFoundationSqlBlock(jdbc).execute())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
    }

    @Test
    void failureRecordAndRealListenerRetryRetainCommittedDdlAndData() {
        JdbcTemplate jdbc = fresh("retry");
        jdbc.execute("CREATE TABLE de_standalone_version (installed_rank int PRIMARY KEY,version varchar(50),success boolean NOT NULL)");
        DeStandaloneVersionRepository versions = mock(DeStandaloneVersionRepository.class);
        when(versions.findRecords()).thenAnswer(call -> jdbc.query("SELECT * FROM de_standalone_version ORDER BY installed_rank DESC", (rs, i) -> {
            DeStandaloneVersion row = new DeStandaloneVersion();
            row.setId(rs.getInt("installed_rank"));
            row.setVersion(rs.getString("version"));
            row.setSuccess(rs.getBoolean("success"));
            return row;
        }));
        when(versions.saveAndFlush(any(DeStandaloneVersion.class))).thenAnswer(call -> {
            DeStandaloneVersion row = call.getArgument(0);
            jdbc.update("INSERT INTO de_standalone_version VALUES(?,?,?) ON DUPLICATE KEY UPDATE success=VALUES(success)", row.getId(), row.getVersion(), row.getSuccess());
            return row;
        });
        JdbcTemplate interrupted = spy(jdbc);
        doAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("CREATE TABLE `de_ent_tenant`")) {
                jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'retained-user','合成用户')");
                throw new IllegalStateException("synthetic interruption after committed user DDL");
            }
            jdbc.execute(sql);
            return null;
        }).when(interrupted).execute(any(String.class));
        runListener(versions, new EnterpriseFoundationSqlBlock(interrupted), true);
        assertThat(jdbc.queryForObject("SELECT success FROM de_standalone_version ORDER BY installed_rank DESC LIMIT 1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_user", Integer.class)).isEqualTo(1);
        runListener(versions, new EnterpriseFoundationSqlBlock(jdbc), false);
        assertThat(jdbc.queryForObject("SELECT success FROM de_standalone_version ORDER BY installed_rank DESC LIMIT 1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_user", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_standalone_version", Integer.class)).isEqualTo(2);
        runListener(versions, new EnterpriseFoundationSqlBlock(jdbc), false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_standalone_version", Integer.class)).isEqualTo(2);
    }

    @Test
    void literalDefaultCaseDriftFailsBeforeCreatingOtherTables() {
        JdbcTemplate jdbc = fresh("defaultcase");
        jdbc.execute(FoundationSchema.TABLES.getFirst().ddl().replace("DEFAULT 'DISABLED'", "DEFAULT 'disabled'"));
        assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_user' AND COLUMN_NAME='status'", String.class)).isEqualTo("disabled");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'synthetic-user','合成用户')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        assertThatThrownBy(() -> new EnterpriseFoundationSqlBlock(jdbc).execute())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_tenant'", Integer.class)).isZero();
    }

    @Test
    void startupVerifierRejectsLiteralDefaultCaseDriftInEveryTable() {
        for (var table : FoundationSchema.TABLES) {
            JdbcTemplate jdbc = migrated("defaultstartup");
            jdbc.execute("ALTER TABLE `" + table.name() + "` ALTER COLUMN status SET DEFAULT 'disabled'");
            assertThatThrownBy(() -> new FoundationSchemaVerifier(new EnterpriseFoundationSqlBlock(jdbc)).run(null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
            assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='status'", String.class, table.name())).isEqualTo("disabled");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `" + table.name() + "`", Integer.class)).isZero();
        }
    }

    @Test
    void prefixIndexDriftFailsBeforeCreatingOtherTables() {
        JdbcTemplate jdbc = fresh("prefix");
        jdbc.execute(FoundationSchema.TABLES.getFirst().ddl().replace("UNIQUE KEY `uk_user_name` (`username`)", "UNIQUE KEY `uk_user_name` (`username`(1))"));
        assertThat(jdbc.queryForObject("SELECT SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_user' AND INDEX_NAME='uk_user_name'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> new EnterpriseFoundationSqlBlock(jdbc).execute())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_tenant'", Integer.class)).isZero();
    }

    @Test
    void startupVerifierRejectsPrefixUniqueSchoolCodeIndexWithoutRepair() {
        JdbcTemplate jdbc = migrated("prefixstartup");
        jdbc.execute("ALTER TABLE de_ent_org DROP INDEX uk_org_school_code, ADD UNIQUE INDEX uk_org_school_code (school_code(1))");
        principals(jdbc);
        school(jdbc, 101, 10, "001");
        assertThatThrownBy(() -> school(jdbc, 102, 10, "002"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
        assertThatThrownBy(() -> new FoundationSchemaVerifier(new EnterpriseFoundationSqlBlock(jdbc)).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
        assertThat(jdbc.queryForObject("SELECT SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_org' AND INDEX_NAME='uk_org_school_code'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_org", Integer.class)).isEqualTo(1);
    }

    @Test
    void startupVerifierRejectsDescendingOrAdditionalFunctionalIndexParts() {
        for (String definition : List.of("school_code DESC", "school_code, ((CHAR_LENGTH(school_code)))")) {
            JdbcTemplate jdbc = migrated("indexparts");
            jdbc.execute("ALTER TABLE de_ent_org DROP INDEX uk_org_school_code, ADD UNIQUE INDEX uk_org_school_code (" + definition + ")");
            List<java.util.Map<String, Object>> before = jdbc.queryForList("SELECT SEQ_IN_INDEX,COLUMN_NAME,COLLATION FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_org' AND INDEX_NAME='uk_org_school_code' ORDER BY SEQ_IN_INDEX");
            if (definition.contains("DESC")) {
                assertThat(before).hasSize(1);
                assertThat(before.getFirst().get("COLLATION")).isEqualTo("D");
            } else {
                assertThat(before).hasSize(2);
                assertThat(before.get(1).get("COLUMN_NAME")).isNull();
            }
            assertThatThrownBy(() -> new FoundationSchemaVerifier(new EnterpriseFoundationSqlBlock(jdbc)).run(null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
            assertThat(jdbc.queryForList("SELECT SEQ_IN_INDEX,COLUMN_NAME,COLLATION FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_org' AND INDEX_NAME='uk_org_school_code' ORDER BY SEQ_IN_INDEX")).isEqualTo(before);
        }
    }

    @Test
    void checkLiteralContentsCannotBeNormalizedIntoAnotherStatus() {
        for (String literal : List.of("ACT_utf8mb4IVE", "ACT`IVE")) {
            JdbcTemplate jdbc = migrated("checkliteral");
            principals(jdbc);
            jdbc.execute("ALTER TABLE de_ent_user DROP CHECK ck_user_status, ADD CONSTRAINT ck_user_status CHECK (status IN ('DISABLED','" + literal + "')) ENFORCED");
            assertThat(jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_user_status'", String.class)).contains(literal);
            assertThatThrownBy(() -> jdbc.update("UPDATE de_ent_user SET status='ACTIVE' WHERE id=1"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(FoundationMigrationTest::integrityFailure);
            assertThatThrownBy(() -> new FoundationSchemaVerifier(new EnterpriseFoundationSqlBlock(jdbc)).run(null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
            assertThat(jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_user_status'", String.class)).contains(literal);
            assertThat(jdbc.queryForObject("SELECT status FROM de_ent_user WHERE id=1", String.class)).isEqualTo("DISABLED");
        }
    }

    private static void integrityFailure(Throwable error) {
        Throwable root = ((org.springframework.dao.DataAccessException) error).getRootCause();
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) root).getErrorCode()).isIn(1062, 1216, 1217, 1451, 1452, 3819, 1048);
    }

    @Test
    void startupVerifierDetectsMissingOrDriftedSchemaWithoutRepair() {
        JdbcTemplate jdbc = fresh("verify");
        EnterpriseFoundationSqlBlock block = new EnterpriseFoundationSqlBlock(jdbc);
        assertThatThrownBy(() -> new FoundationSchemaVerifier(block).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'de_ent_%'", Integer.class)).isZero();
        block.execute();
        jdbc.execute("ALTER TABLE de_ent_user ALTER CHECK ck_user_epoch NOT ENFORCED");
        assertThatThrownBy(() -> new FoundationSchemaVerifier(block).run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT ENFORCED FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_user_epoch'", String.class)).isEqualTo("NO");
    }

    private static void runListener(DeStandaloneVersionRepository versions, EnterpriseFoundationSqlBlock block, boolean failure) {
        var previous = SpringContextUtil.getApplicationContext();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("foundationBlock", EnterpriseFoundationSqlBlock.class, () -> block);
            context.refresh();
            new SpringContextUtil().setApplicationContext(context);
            InitSqlListener listener = new InitSqlListener();
            ReflectionTestUtils.setField(listener, "deStandaloneVersionRepository", versions);
            if (failure) assertThatThrownBy(() -> listener.run(null)).isInstanceOf(RuntimeException.class);
            else listener.run(null);
        } finally {
            new SpringContextUtil().setApplicationContext(previous);
        }
    }
}
