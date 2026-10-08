package io.dataease.enterprise.foundation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-only validation after InitSqlListener, including previously successful migrations. */
public final class FoundationSchemaVerifier implements ApplicationRunner, Ordered {
    private final JdbcTemplate jdbc;
    private final List<FoundationSchema.Table> target;
    private final Set<String> tableNames;

    public FoundationSchemaVerifier(JdbcTemplate jdbc) {
        this(jdbc, FoundationSchema.TABLES);
    }

    // Internal snapshot constructor for evolution tests; not a configurable/default-allow production bean.
    FoundationSchemaVerifier(JdbcTemplate jdbc, List<FoundationSchema.Table> target) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.target = List.copyOf(target);
        if (this.target.isEmpty()) throw new IllegalArgumentException("Current schema target is required");
        this.tableNames = this.target.stream().map(FoundationSchema.Table::name).collect(Collectors.toUnmodifiableSet());
        if (tableNames.size() != this.target.size() || tableNames.stream().anyMatch(name -> !name.matches("de_ent_[a-z0-9_]+"))) {
            throw new IllegalArgumentException("Current schema target must contain unique enterprise table names");
        }
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Escape literal underscores; reserve the whole namespace, including views and case variants.
        var actualNames = jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND LOWER(TABLE_NAME) LIKE 'de!_ent!_%' ESCAPE '!'", String.class);
        if (actualNames.stream().anyMatch(name -> !tableNames.contains(name))) {
            throw new IllegalStateException("Enterprise foundation schema mismatch: undeclared reserved table");
        }
        for (var table : target) {
            FoundationSchema.require(FoundationSchema.exists(jdbc, table), table);
            FoundationSchema.verify(jdbc, table);
        }
    }
}
