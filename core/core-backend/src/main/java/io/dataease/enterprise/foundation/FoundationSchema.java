package io.dataease.enterprise.foundation;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MySQL 8-only, append-only schema contract. No legacy ownership is inferred. */
public final class FoundationSchema {
    record Column(String name, String type, boolean nullable, String defaultValue, String comment) {
        String ddl() {
            return "`" + name + "` " + type + (nullable ? " NULL" : " NOT NULL")
                    + (defaultValue == null ? "" : " DEFAULT " + defaultValue)
                    + " COMMENT '" + comment + "'";
        }
    }

    record Key(String name, boolean unique, List<String> columns) {
        String ddl() {
            String fields = String.join(",", columns.stream().map(v -> "`" + v + "`").toList());
            return name.equals("PRIMARY") ? "PRIMARY KEY (" + fields + ")"
                    : (unique ? "UNIQUE KEY " : "KEY ") + "`" + name + "` (" + fields + ")";
        }
    }

    record ForeignKey(String name, List<String> columns, String target, List<String> targetColumns) {
        String ddl() {
            return "CONSTRAINT `" + name + "` FOREIGN KEY (" + quoted(columns) + ") REFERENCES `"
                    + target + "` (" + quoted(targetColumns) + ") ON DELETE RESTRICT ON UPDATE RESTRICT";
        }
    }

    record Table(String name, String comment, List<Column> columns, List<Key> keys,
                 List<ForeignKey> foreignKeys, Map<String, String> checks) {
        String ddl() {
            List<String> parts = new ArrayList<>();
            columns.forEach(v -> parts.add(v.ddl()));
            keys.forEach(v -> parts.add(v.ddl()));
            foreignKeys.forEach(v -> parts.add(v.ddl()));
            checks.forEach((k, v) -> parts.add("CONSTRAINT `" + k + "` CHECK (" + v + ") ENFORCED"));
            return "CREATE TABLE `" + name + "` (" + String.join(",\n", parts)
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='" + comment + "'";
        }
    }

    private FoundationSchema() {
    }

    static String quoted(List<String> values) {
        return String.join(",", values.stream().map(v -> "`" + v + "`").toList());
    }

    static Column c(String name, String type, boolean nullable, String def, String comment) {
        return new Column(name, type, nullable, def, comment);
    }

    static Key k(String name, boolean unique, String... columns) {
        return new Key(name, unique, List.of(columns));
    }

    static ForeignKey fk(String name, String target, String column) {
        return new ForeignKey(name, List.of(column), target, List.of("id"));
    }

    static List<Column> common() {
        return new ArrayList<>(List.of(
                c("id", "bigint", false, null, "记录主键，服务端生成"),
                c("version", "bigint", false, "1", "乐观锁修订，更新加一"),
                c("created_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "创建时间UTC"),
                c("updated_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "修改时间UTC，应用显式更新"),
                c("created_by", "bigint", true, null, "创建平台用户，NULL仅受控系统操作"),
                c("updated_by", "bigint", true, null, "修改平台用户，NULL仅受控系统操作")));
    }

    static Table table(String suffix, String comment, List<Column> fields, List<Key> keys,
                       List<ForeignKey> refs, Map<String, String> specificChecks) {
        String name = "de_ent_" + suffix;
        List<Column> columns = common();
        columns.addAll(fields);
        List<Key> indices = new ArrayList<>(List.of(k("PRIMARY", true, "id")));
        indices.addAll(keys);
        List<ForeignKey> foreignKeys = new ArrayList<>(List.of(
                fk("fk_" + suffix + "_created_by", "de_ent_user", "created_by"),
                fk("fk_" + suffix + "_updated_by", "de_ent_user", "updated_by")));
        // Explicit support indices keep SHOW INDEX independent of engine-generated names.
        indices.add(k("ix_" + suffix + "_created_by", false, "created_by"));
        indices.add(k("ix_" + suffix + "_updated_by", false, "updated_by"));
        foreignKeys.addAll(refs);
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("ck_" + suffix + "_positive", "(`id` > 0) AND (`version` > 0)");
        checks.put("ck_" + suffix + "_status", "`status` IN ('DISABLED','ACTIVE')");
        checks.putAll(specificChecks);
        return new Table(name, comment, columns, indices, foreignKeys, checks);
    }

    static Map<String, String> checks(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    static final List<Table> TABLES = List.of(
            table("user", "平台全局账号", List.of(
                    c("username", "varchar(128)", false, null, "精确匹配的登录账号，保留占用"),
                    c("display_name", "varchar(128)", false, null, "用户展示名"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE"),
                    c("identity_epoch", "bigint", false, "1", "全局身份安全修订号"),
                    c("legacy_uid", "bigint", true, null, "经审查确认的旧账号编号")),
                    List.of(k("uk_user_name", true, "username"), k("uk_user_legacy", true, "legacy_uid"),
                            k("ix_user_status", false, "status", "id")), List.of(),
                    checks("ck_user_epoch", "`identity_epoch` > 0", "ck_user_legacy", "(`legacy_uid` IS NULL) OR (`legacy_uid` > 0)",
                            "ck_user_username", "(CHAR_LENGTH(`username`) > 0) AND (`username` = TRIM(`username`))")),
            table("tenant", "集团租户主表", List.of(
                    c("code", "varchar(64)", false, null, "不可复用的集团代码"),
                    c("name", "varchar(128)", false, null, "集团展示名称"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE，完成开通才能启用"),
                    c("access_epoch", "bigint", false, "1", "集团安全修订号")),
                    List.of(k("uk_tenant_code", true, "code"), k("ix_tenant_status", false, "status", "id")), List.of(),
                    checks("ck_tenant_epoch", "`access_epoch` > 0", "ck_tenant_code", "(CHAR_LENGTH(`code`) > 0) AND (`code` = TRIM(`code`))")),
            table("tenant_member", "全局账号在集团的成员身份", List.of(
                    c("tenant_id", "bigint", false, null, "唯一归属集团ID"),
                    c("user_id", "bigint", false, null, "全局账号ID"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE")),
                    List.of(k("uk_member_tenant_id", true, "tenant_id", "id"), k("uk_member_user", true, "tenant_id", "user_id"),
                            k("ix_member_user", false, "user_id", "status", "tenant_id")),
                    List.of(fk("fk_member_tenant", "de_ent_tenant", "tenant_id"), fk("fk_member_user", "de_ent_user", "user_id")), checks()),
            table("org", "学校机构组织树及权威学校编号", List.of(
                    c("tenant_id", "bigint", false, null, "唯一归属集团ID"),
                    c("parent_id", "bigint", true, null, "集团内父组织，根节点NULL"),
                    c("kind", "varchar(16)", false, null, "SCHOOL或DEPARTMENT"),
                    c("name", "varchar(128)", false, null, "组织名称"),
                    c("school_code", "varchar(64)", true, null, "仅学校填写，全局唯一业务学校编号"),
                    c("school_id", "bigint", true, null, "机构所属学校，集团直属机构NULL"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE")),
                    List.of(k("uk_org_tenant_id", true, "tenant_id", "id"), k("uk_org_school_code", true, "school_code"),
                            k("ix_org_parent", false, "tenant_id", "parent_id", "status"), k("ix_org_school", false, "tenant_id", "school_id", "status")),
                    List.of(fk("fk_org_tenant", "de_ent_tenant", "tenant_id"),
                            new ForeignKey("fk_org_parent", List.of("tenant_id", "parent_id"), "de_ent_org", List.of("tenant_id", "id")),
                            new ForeignKey("fk_org_school", List.of("tenant_id", "school_id"), "de_ent_org", List.of("tenant_id", "id"))),
                    checks("ck_org_kind", "((`kind` = 'SCHOOL') AND (`school_code` IS NOT NULL) AND (`school_id` IS NULL)) OR ((`kind` = 'DEPARTMENT') AND (`school_code` IS NULL))",
                            "ck_org_parent", "(`parent_id` IS NULL) OR (`parent_id` <> `id`)",
                            "ck_org_school", "(`school_id` IS NULL) OR (`school_id` <> `id`)",
                            "ck_org_school_code", "(`school_code` IS NULL) OR ((CHAR_LENGTH(`school_code`) > 0) AND (`school_code` = TRIM(`school_code`)))")));

    public static boolean owns(String table) {
        return TABLES.stream().anyMatch(v -> v.name().equals(table));
    }

    static boolean exists(JdbcTemplate jdbc, Table table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                Integer.class, table.name()) == 1;
    }

    static void verify(JdbcTemplate jdbc, Table table) {
        Map<String, Object> meta = jdbc.queryForMap("SELECT ENGINE,TABLE_COLLATION,TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", table.name());
        require("InnoDB".equals(meta.get("ENGINE")) && "utf8mb4_0900_bin".equals(meta.get("TABLE_COLLATION"))
                && table.comment().equals(meta.get("TABLE_COMMENT")), table);
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLUMN_COMMENT,COLLATION_NAME,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION", table.name());
        require(columns.size() == table.columns().size(), table);
        for (int i = 0; i < columns.size(); i++) {
            Column wanted = table.columns().get(i);
            Map<String, Object> got = columns.get(i);
            require(wanted.name().equals(got.get("COLUMN_NAME")) && wanted.type().equals(got.get("COLUMN_TYPE"))
                    && (wanted.nullable() ? "YES" : "NO").equals(got.get("IS_NULLABLE"))
                    && defaultMatches(wanted, got.get("COLUMN_DEFAULT"))
                    && wanted.comment().equals(got.get("COLUMN_COMMENT"))
                    && (!wanted.type().startsWith("varchar") || "utf8mb4_0900_bin".equals(got.get("COLLATION_NAME")))
                    && ("".equals(got.get("EXTRA")) || "DEFAULT_GENERATED".equals(got.get("EXTRA"))), table);
        }
        List<Map<String, Object>> indices = jdbc.queryForList("SELECT INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,COLLATION,INDEX_TYPE,IS_VISIBLE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY INDEX_NAME,SEQ_IN_INDEX", table.name());
        Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
        indices.forEach(row -> byName.computeIfAbsent(row.get("INDEX_NAME").toString(), ignored -> new ArrayList<>()).add(row));
        require(byName.size() == table.keys().size(), table);
        for (Key key : table.keys()) {
            List<Map<String, Object>> parts = byName.get(key.name());
            require(parts != null && parts.size() == key.columns().size(), table);
            for (int i = 0; i < parts.size(); i++) {
                Map<String, Object> part = parts.get(i);
                require(((Number) part.get("NON_UNIQUE")).intValue() == (key.unique() ? 0 : 1)
                        && ((Number) part.get("SEQ_IN_INDEX")).intValue() == i + 1
                        && key.columns().get(i).equals(part.get("COLUMN_NAME")) && part.get("SUB_PART") == null
                        && "A".equals(part.get("COLLATION")) && "BTREE".equals(part.get("INDEX_TYPE"))
                        && "YES".equals(part.get("IS_VISIBLE")), table);
            }
        }
        List<Map<String, Object>> refs = jdbc.queryForList("SELECT k.CONSTRAINT_NAME,GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION) AS fields,k.REFERENCED_TABLE_SCHEMA,k.REFERENCED_TABLE_NAME,GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION) AS targets,r.DELETE_RULE,r.UPDATE_RULE FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND r.TABLE_NAME=k.TABLE_NAME WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=? GROUP BY k.CONSTRAINT_NAME,k.REFERENCED_TABLE_SCHEMA,k.REFERENCED_TABLE_NAME,r.DELETE_RULE,r.UPDATE_RULE", table.name());
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        require(refs.size() == table.foreignKeys().size(), table);
        for (ForeignKey key : table.foreignKeys()) {
            require(refs.stream().anyMatch(row -> key.name().equals(row.get("CONSTRAINT_NAME"))
                    && String.join(",", key.columns()).equals(row.get("fields")) && database.equals(row.get("REFERENCED_TABLE_SCHEMA"))
                    && key.target().equals(row.get("REFERENCED_TABLE_NAME")) && String.join(",", key.targetColumns()).equals(row.get("targets"))
                    && "RESTRICT".equals(row.get("DELETE_RULE")) && "RESTRICT".equals(row.get("UPDATE_RULE"))), table);
        }
        List<Map<String, Object>> constraints = jdbc.queryForList("SELECT t.CONSTRAINT_NAME,t.ENFORCED,c.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME=? AND t.CONSTRAINT_TYPE='CHECK'", table.name());
        require(constraints.size() == table.checks().size(), table);
        table.checks().forEach((name, expr) -> require(constraints.stream().anyMatch(row -> name.equals(row.get("CONSTRAINT_NAME"))
                && "YES".equals(row.get("ENFORCED")) && normalizeCheck(expr).equals(normalizeCheck(row.get("CHECK_CLAUSE").toString()))), table));
    }

    // Normalize only known function names. Literal case, whitespace and embedded quotes are data.
    static boolean defaultMatches(Column column, Object actual) {
        String expected = column.defaultValue();
        if (expected == null || actual == null) return expected == null && actual == null;
        String value = actual.toString();
        if (expected.startsWith("'") && expected.endsWith("'")) {
            return expected.substring(1, expected.length() - 1).replace("''", "'").equals(value);
        }
        return expected.matches("CURRENT_TIMESTAMP\\([0-6]\\)")
                ? expected.equalsIgnoreCase(value) : expected.equals(value);
    }

    // MySQL adds literal charset introducers, operator/function casing and outer brackets.
    // Preserve inner brackets: changing AND/OR grouping must fail validation.
    static String normalizeCheck(String value) {
        String[] segments = value.replace("_utf8mb4", "").replace("\\'", "'").replace("`", "").split("'", -1);
        for (int i = 0; i < segments.length; i += 2) {
            segments[i] = segments[i].replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        }
        String result = String.join("'", segments);
        while (result.startsWith("(") && result.endsWith(")") && wrapsWholeExpression(result)) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    static boolean wrapsWholeExpression(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (expression.charAt(i) == '(') depth++;
            if (expression.charAt(i) == ')' && --depth == 0 && i != expression.length() - 1) return false;
        }
        return depth == 0;
    }

    static void require(boolean condition, Table table) {
        if (!condition) throw new IllegalStateException("Enterprise foundation schema mismatch: " + table.name());
    }
}
