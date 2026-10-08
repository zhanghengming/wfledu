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
        Key { columns = List.copyOf(columns); }
        String ddl() {
            String fields = String.join(",", columns.stream().map(v -> "`" + v + "`").toList());
            return name.equals("PRIMARY") ? "PRIMARY KEY (" + fields + ")"
                    : (unique ? "UNIQUE KEY " : "KEY ") + "`" + name + "` (" + fields + ")";
        }
    }

    record ForeignKey(String name, List<String> columns, String target, List<String> targetColumns) {
        ForeignKey { columns = List.copyOf(columns); targetColumns = List.copyOf(targetColumns); }
        String ddl() {
            return "CONSTRAINT `" + name + "` FOREIGN KEY (" + quoted(columns) + ") REFERENCES `"
                    + target + "` (" + quoted(targetColumns) + ") ON DELETE RESTRICT ON UPDATE RESTRICT";
        }
    }

    record Table(String name, String comment, List<Column> columns, List<Key> keys,
                 List<ForeignKey> foreignKeys, Map<String, String> checks) {
        Table {
            columns = List.copyOf(columns);
            keys = List.copyOf(keys);
            foreignKeys = List.copyOf(foreignKeys);
            checks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(checks));
        }
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

    // Current production target. A later version must replace this target without modifying V41.
    static final List<Table> TABLES = List.copyOf(FoundationSchemaV41.TABLES);

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
        String[] segments = value.replace("\\'", "'").split("'", -1);
        for (int i = 0; i < segments.length; i += 2) {
            segments[i] = segments[i].replace("_utf8mb4", "").replace("`", "")
                    .replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
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
