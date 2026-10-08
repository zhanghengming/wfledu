package io.dataease.enterprise.foundation;

import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EnterpriseResourceSqlBlock implements SqlBlock {
    private final JdbcTemplate jdbc;
    public EnterpriseResourceSqlBlock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Version getVersion() { return new Version("4.5"); }
    @Override public String getVersionGroup() { return "4"; }
    @Override public void execute() {
        FoundationSchemaV44.TABLES.forEach(t -> FoundationSchema.verify(jdbc, t));
        for (var t : FoundationSchemaV45.ADDITIONS) if (FoundationSchema.exists(jdbc, t)) FoundationSchema.verify(jdbc, t);
        for (var t : FoundationSchemaV45.ADDITIONS) {
            if (!FoundationSchema.exists(jdbc, t)) jdbc.execute(t.ddl());
            FoundationSchema.verify(jdbc, t);
        }
    }
}
