package io.dataease.enterprise.foundation;

import io.dataease.initSql.SqlBlock;
import io.dataease.initSql.Version;
import org.springframework.jdbc.core.JdbcTemplate;

/** Frozen 4.3; partial MySQL DDL is retained and verified before retry. */
public final class EnterpriseAuthoritySqlBlock implements SqlBlock {
    private final JdbcTemplate jdbc;
    public EnterpriseAuthoritySqlBlock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Version getVersion() { return new Version("4.3"); }
    @Override public String getVersionGroup() { return "4"; }
    @Override public void execute() {
        FoundationSchemaV42.TABLES.forEach(table -> FoundationSchema.verify(jdbc, table));
        for (var table : FoundationSchemaV43.ADDITIONS) if (FoundationSchema.exists(jdbc, table)) FoundationSchema.verify(jdbc, table);
        for (var table : FoundationSchemaV43.ADDITIONS) {
            if (!FoundationSchema.exists(jdbc, table)) jdbc.execute(table.ddl());
            FoundationSchema.verify(jdbc, table);
        }
    }
}
