package io.dataease.enterprise.foundation;

import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import org.springframework.jdbc.core.JdbcTemplate;

/** DDL implicitly commits in MySQL. Validate old tables before creating any new table. */
public final class EnterpriseFoundationSqlBlock implements SqlBlock {
    private final JdbcTemplate jdbc;

    public EnterpriseFoundationSqlBlock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Version getVersion() {
        return new Version("4.1");
    }

    @Override
    public String getVersionGroup() {
        return "4";
    }

    public void verifySchema() {
        for (FoundationSchema.Table table : FoundationSchema.TABLES) {
            FoundationSchema.require(FoundationSchema.exists(jdbc, table), table);
            FoundationSchema.verify(jdbc, table);
        }
    }

    @Override
    public void execute() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        if (version == null || !version.matches("8\\.0\\.\\d+.*")
                || Integer.parseInt(version.split("[.-]")[2]) < 16) {
            throw new IllegalStateException("Enterprise foundation requires MySQL 8.0.16 or later in 8.0");
        }
        for (FoundationSchema.Table table : FoundationSchema.TABLES) {
            if (FoundationSchema.exists(jdbc, table)) FoundationSchema.verify(jdbc, table);
        }
        for (FoundationSchema.Table table : FoundationSchema.TABLES) {
            if (!FoundationSchema.exists(jdbc, table)) jdbc.execute(table.ddl());
            FoundationSchema.verify(jdbc, table);
        }
    }
}
