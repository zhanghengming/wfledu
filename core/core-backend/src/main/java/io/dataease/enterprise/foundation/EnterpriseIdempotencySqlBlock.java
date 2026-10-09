package io.dataease.enterprise.foundation;

import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EnterpriseIdempotencySqlBlock implements SqlBlock {
    private final JdbcTemplate jdbc;
    public EnterpriseIdempotencySqlBlock(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Version getVersion() { return new Version("4.7"); }
    @Override public String getVersionGroup() { return "4"; }
    @Override public void execute() {
        FoundationSchemaV46.TABLES.forEach(t -> FoundationSchema.verify(jdbc,t));
        for (var t:FoundationSchemaV47.ADDITIONS) if (FoundationSchema.exists(jdbc,t)) FoundationSchema.verify(jdbc,t);
        for (var t:FoundationSchemaV47.ADDITIONS) {
            if (!FoundationSchema.exists(jdbc,t)) jdbc.execute(t.ddl());
            FoundationSchema.verify(jdbc,t);
        }
    }
}
