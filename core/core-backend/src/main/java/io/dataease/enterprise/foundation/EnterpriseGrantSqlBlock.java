package io.dataease.enterprise.foundation;

import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import org.springframework.jdbc.core.JdbcTemplate;

/** Append-only migration; verify all existing objects before committing any new DDL. */
public final class EnterpriseGrantSqlBlock implements SqlBlock {
    private final JdbcTemplate jdbc;
    public EnterpriseGrantSqlBlock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Version getVersion() { return new Version("4.6"); }
    @Override public String getVersionGroup() { return "4"; }
    @Override public void execute() {
        FoundationSchemaV45.TABLES.forEach(t -> FoundationSchema.verify(jdbc,t));
        for (var t : FoundationSchemaV46.ADDITIONS) if (FoundationSchema.exists(jdbc,t)) FoundationSchema.verify(jdbc,t);
        for (var t : FoundationSchemaV46.ADDITIONS) {
            if (!FoundationSchema.exists(jdbc,t)) jdbc.execute(t.ddl());
            FoundationSchema.verify(jdbc,t);
        }
    }
}
