package io.dataease.enterprise.foundation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class GeneratedColumnSchemaTest {
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }

    private static FoundationSchema.Table target() {
        return new FoundationSchema.Table("de_ent_generated_probe", "Synthetic generated metadata",
                List.of(FoundationSchema.c("id", "bigint", false, null, "Assigned ID"),
                        FoundationSchema.c("resource_id", "bigint", true, null, "Nullable resource"),
                        FoundationSchema.stored("resource_key", "bigint", "coalesce(`resource_id`,0)", "Resource slot")),
                List.of(new FoundationSchema.Key("PRIMARY", true, List.of("id")),
                        new FoundationSchema.Key("uk_generated", true, List.of("resource_key"))),
                List.of(), Map.of());
    }
    private static JdbcTemplate fixture(String ddl) {
        var jdbc = FoundationMigrationTest.fresh("generated");
        jdbc.execute(ddl);
        return jdbc;
    }
    private static void rejectsUnchanged(JdbcTemplate jdbc, FoundationSchema.Table table) {
        var before = jdbc.queryForList("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION", table.name());
        assertThatThrownBy(() -> FoundationSchema.verify(jdbc, table)).isInstanceOf(IllegalStateException.class).hasMessageContaining("schema mismatch");
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION", table.name())).isEqualTo(before);
    }
    @Test void storedSlotMetadataAndNullNaturalKeyAreActuallyEnforced() {
        var table = target(); var jdbc = fixture(table.ddl()); FoundationSchema.verify(jdbc, table);
        var row = jdbc.queryForMap("SELECT IS_NULLABLE,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='resource_key'", table.name());
        assertThat(row.get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(row.get("EXTRA")).isEqualTo("STORED GENERATED");
        assertThat(row.get("GENERATION_EXPRESSION")).isEqualTo("coalesce(`resource_id`,0)");
        jdbc.update("INSERT INTO de_ent_generated_probe(id,resource_id) VALUES(1,NULL),(2,8)");
        assertThat(jdbc.queryForList("SELECT resource_key FROM de_ent_generated_probe ORDER BY id", Long.class)).containsExactly(0L,8L);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO de_ent_generated_probe(id,resource_id) VALUES(3,NULL)")).isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
    }
    @Test void changedGenerationExpressionFailsWithoutRepair() {
        var table = target();var jdbc = fixture(table.ddl().replace("coalesce(`resource_id`,0)", "coalesce(`resource_id`,1)"));
        assertThat(jdbc.queryForObject("SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='resource_key'",String.class,table.name())).isEqualTo("coalesce(`resource_id`,1)");
        rejectsUnchanged(jdbc,table);
    }
    @Test void virtualAndWritableReplacementBothFailWithoutRepair() {
        var table = target();
        for (String replacement : List.of("VIRTUAL", "writable")) {
            String ddl = replacement.equals("VIRTUAL") ? table.ddl().replace(" STORED ", " VIRTUAL ")
                    : table.ddl().replace(" GENERATED ALWAYS AS (coalesce(`resource_id`,0)) STORED", " NULL");
            var jdbc = fixture(ddl);
            assertThat(jdbc.queryForObject("SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='resource_key'",String.class,table.name()))
                    .isEqualTo(replacement.equals("VIRTUAL") ? "VIRTUAL GENERATED" : "");
            rejectsUnchanged(jdbc,table);
        }
    }
    @Test void literalCaseWhitespaceAndIntroducerTextRemainDistinct() {
        var table = new FoundationSchema.Table("de_ent_generated_probe", "Synthetic literal metadata",
                List.of(FoundationSchema.c("id","bigint",false,null,"Assigned ID"),
                        FoundationSchema.stored("literal_key","varchar(32)","_utf8mb4'ACT_utf8mb4IVE'","_utf8mb4\\'ACT_utf8mb4IVE\\'","Literal slot")),
                List.of(new FoundationSchema.Key("PRIMARY",true,List.of("id"))),List.of(),Map.of());
        for (String literal : List.of("ACT_utf8mb4IVE","act_utf8mb4ive","ACT_utf8mb4IVE ","ACTIVE")) {
            var jdbc = fixture(table.ddl().replace("ACT_utf8mb4IVE",literal));
            assertThat(jdbc.queryForObject("SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='literal_key'",String.class,table.name())).isEqualTo("_utf8mb4\\'"+literal+"\\'");
            if (literal.equals("ACT_utf8mb4IVE")) FoundationSchema.verify(jdbc,table);else rejectsUnchanged(jdbc,table);
        }
    }
    @Test void ordinaryColumnCannotMasqueradeAsGeneratedColumn() {
        var expected = new FoundationSchema.Table("de_ent_generated_probe","Synthetic ordinary metadata",
                List.of(FoundationSchema.c("id","bigint",false,null,"Assigned ID"),
                        FoundationSchema.c("slot","bigint",true,null,"Ordinary slot")),
                List.of(new FoundationSchema.Key("PRIMARY",true,List.of("id"))),List.of(),Map.of());
        var jdbc = fixture(expected.ddl().replace("`slot` bigint NULL", "`slot` bigint GENERATED ALWAYS AS (1) STORED"));
        rejectsUnchanged(jdbc,expected);
    }
    @Test void generatedTypeCommentAndIndexDriftRemainRejected() {
        var table = target();
        for (String ddl : List.of(table.ddl().replace("`resource_key` bigint", "`resource_key` int"),
                table.ddl().replace("Resource slot","Changed slot"),
                table.ddl().replace("(`resource_key`)","(`resource_key` DESC)"))) {
            var jdbc = fixture(ddl);rejectsUnchanged(jdbc,table);
        }
    }
    @Test void explicitlyWritingGeneratedSlotIsRejectedByMySql() {
        var jdbc = fixture(target().ddl());
        Throwable thrown = catchThrowable(() -> jdbc.update("INSERT INTO de_ent_generated_probe(id,resource_key) VALUES(1,7)"));
        assertThat(thrown).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var cause = (java.sql.SQLException)((org.springframework.dao.DataAccessException)thrown).getRootCause();
        assertThat(cause.getErrorCode()).isEqualTo(3105);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_generated_probe",Long.class)).isZero();
    }
    @Test void generatedDescriptorRejectsInventedNonNullOrDefaultContract() {
        assertThatThrownBy(() -> new FoundationSchema.Column("slot","bigint",false,null,"slot",new FoundationSchema.Generation("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoundationSchema.Column("slot","bigint",true,"0","slot",new FoundationSchema.Generation("1"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void frozenV41ThroughV45DdlAndDescriptorsRemainUnchanged() throws Exception {
        var expected = Map.ofEntries(
                Map.entry("de_ent_user","02924549b95dbfdfa7c7ebc07883b0cd5275289e8faa87db3e44773c2e2df9f9"),
                Map.entry("de_ent_tenant","61000f6a8fa9be13c6f996b9fe1c6b3d14c37d22905cb9d5bb5b9ac6ab0acc57"),
                Map.entry("de_ent_tenant_member","fa705d87b27e2c1930308ce6526e0b2d1971edd4e83026d70cf4afd9355354a9"),
                Map.entry("de_ent_org","29d051269cbc5751f3bdae0620b93c0ba967b8e798b366e63147f796e78d19e4"),
                Map.entry("de_ent_audit_event","f462fe6e98d04e894c7879ac94516e3653c4395aa739889b52e22af96a3eb5c7"),
                Map.entry("de_ent_org_member","05e3ae1221d6d48e7ac3d845b93f0d59696043c62d221ded78b26414f66e7b34"),
                Map.entry("de_ent_role","f955ec2687c2206a2b6734dc12e9cd60525bfe197afe842f727b328790ab55f1"),
                Map.entry("de_ent_role_assignment","3c5a512cccd1a8d7000b50616220f1411f2beb4c4ad97a8b92c565f96e3be466"),
                Map.entry("de_ent_assignment_school","2da3a7a999adf8282ed45d11e8e3507b3494b5a358e38efd0a639a8db549b469"),
                Map.entry("de_ent_subject","50ac3b165dcbfd9dbbf91ee5b17b1277eacdc8a2640395acd8f495c0ecbabc46"),
                Map.entry("de_ent_admin_grant","180612e114ac16b5777bd8d14fce8eb0578638eec81135dc30b2bc09ef14bb88"),
                Map.entry("de_ent_user_credential","7d973f956f32fc7f117ef23cb3c07ca968864c078cdb9ed98bce9172dfdf973b"),
                Map.entry("de_ent_platform_qualification","3d54b1d2f6cc0866f0035a961cdf59b789d8a2bf89abe40b3b391690d00aa5a2"),
                Map.entry("de_ent_login_session","069505f82a7bb28994a9191f0b9ff3b99c11cde5907bc5c254792a485491ad39"),
                Map.entry("de_ent_resource","346190ae5f9949cc74432d8ad00ca76bab5434314b49dc897b0336c0fff28ac9"));
        // These additions were fingerprinted from the reviewed 4.6 package before the constructor fix.
        for (var table : FoundationSchemaV46.ADDITIONS) {
            var expected46 = Map.of("de_ent_grant","beb44e1bbfcc79b304868b410f4e5d491f949136c632eacf0990a2b549b310c6","de_ent_grant_school","a52a0083fe36949a4566edf0b47ed13a7589feb0b41663e72bed52c680cb25d5");
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(table.ddl().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(hash).isEqualTo(expected46.get(table.name()));
        }
        for (var snapshot : List.of(FoundationSchemaV41.TABLES, FoundationSchemaV42.TABLES,
                FoundationSchemaV43.TABLES, FoundationSchemaV44.TABLES, FoundationSchemaV45.TABLES)) {
            assertThatThrownBy(() -> snapshot.add(target())).isInstanceOf(UnsupportedOperationException.class);
            for (var table : snapshot) {
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(table.ddl().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                assertThat(hash).isEqualTo(expected.get(table.name()));
                assertThat(table.columns()).allMatch(column -> column.generation() == null);
                assertThatThrownBy(() -> table.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @Test void coldMigrationEntryPointsNeverReenterCurrentTarget() throws Exception {
        int expected = FoundationSchema.TABLES.size();
        for (String entry : List.of("FoundationSchema", "FoundationSchemaV41", "FoundationSchemaV42",
                "FoundationSchemaV43", "FoundationSchemaV44", "FoundationSchemaV45", "FoundationSchemaV46", "FoundationSchemaV47")) {
            var isolated = new ClassLoader(GeneratedColumnSchemaTest.class.getClassLoader()) {
                @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith("io.dataease.enterprise.foundation.FoundationSchema")) return super.loadClass(name, resolve);
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            byte[] bytes = input.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            };
            Class.forName("io.dataease.enterprise.foundation." + entry, true, isolated);
            var current = Class.forName("io.dataease.enterprise.foundation.FoundationSchema", true, isolated);
            var field = current.getDeclaredField("TABLES"); field.setAccessible(true);
            assertThat((List<?>) field.get(null)).hasSize(expected);
        }
    }

}
