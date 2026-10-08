package io.dataease.enterprise.foundation;

import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import org.springframework.jdbc.core.JdbcTemplate;

/** Append-only 4.4; existing objects must all match before any new DDL. */
public final class EnterpriseCredentialSqlBlock implements SqlBlock {
    private final JdbcTemplate jdbc;
    public EnterpriseCredentialSqlBlock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Version getVersion() { return new Version("4.4"); }
    @Override public String getVersionGroup() { return "4"; }
    @Override public void execute() {
        FoundationSchemaV43.TABLES.forEach(t -> FoundationSchema.verify(jdbc, t));
        for (var t : FoundationSchemaV44.ADDITIONS) if (FoundationSchema.exists(jdbc,t)) FoundationSchema.verify(jdbc,t);
        for (var t : FoundationSchemaV44.ADDITIONS) {
            if (!FoundationSchema.exists(jdbc,t)) jdbc.execute(t.ddl());
            FoundationSchema.verify(jdbc,t);
        }
    }
}
