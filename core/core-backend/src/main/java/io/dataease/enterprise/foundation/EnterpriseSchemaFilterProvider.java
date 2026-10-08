package io.dataease.enterprise.foundation;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

import java.util.Locale;

/** Reserved enterprise objects are managed by SqlBlock, in every runtime mode. */
public final class EnterpriseSchemaFilterProvider implements SchemaFilterProvider {
    static boolean reserved(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith("de_ent_");
    }

    private static final SchemaFilter FILTER = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return true;
        }

        @Override
        public boolean includeTable(Table table) {
            return !reserved(table.getNameIdentifier().getText());
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return !reserved(sequence.getName().getSequenceName().getText());
        }
    };

    @Override
    public SchemaFilter getCreateFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getDropFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getTruncatorFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getMigrateFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getValidateFilter() {
        return FILTER;
    }
}
