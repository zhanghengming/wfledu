package io.dataease.enterprise.foundation;

import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import org.springframework.jdbc.core.JdbcTemplate;

/** The 4.2 step creates only the audit table. MySQL DDL may commit before a failure record is finalized. */
public final class EnterpriseAuditSqlBlock implements SqlBlock {
    private final JdbcTemplate jdbc;

    public EnterpriseAuditSqlBlock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Version getVersion() { return new Version("4.2"); }
    @Override public String getVersionGroup() { return "4"; }

    @Override
    public void execute() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        if (version == null || !version.matches("8\\.0\\.\\d+.*") || Integer.parseInt(version.split("[.-]")[2]) < 16) {
            throw new IllegalStateException("Enterprise audit requires MySQL 8.0.16 or later in 8.0");
        }
        new EnterpriseFoundationSqlBlock(jdbc).verifySchema();
        var table = FoundationSchemaV42.AUDIT;
        if (FoundationSchema.exists(jdbc, table)) FoundationSchema.verify(jdbc, table);
        else jdbc.execute(table.ddl());
        FoundationSchema.verify(jdbc, table);
    }
}
