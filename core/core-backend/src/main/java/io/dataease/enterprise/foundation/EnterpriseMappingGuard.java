package io.dataease.enterprise.foundation;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

import static org.hibernate.cfg.SchemaToolingSettings.HBM2DDL_FILTER_PROVIDER;

/** Runs during SessionFactory construction, before automatic schema work. */
final class EnterpriseMappingGuard implements Integrator {
    private final EnterpriseSchemaFilterProvider filter;
    private final boolean foundationEnabled;

    EnterpriseMappingGuard(EnterpriseSchemaFilterProvider filter, boolean foundationEnabled) {
        this.filter = filter;
        this.foundationEnabled = foundationEnabled;
    }

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrap, SessionFactoryImplementor factory) {
        require(factory.getProperties().get(HBM2DDL_FILTER_PROVIDER) == filter, "schema filter was replaced");
        for (var namespace : metadata.getDatabase().getNamespaces()) {
            for (var table : namespace.getTables()) {
                boolean enterprise = EnterpriseSchemaFilterProvider.reserved(table.getName());
                if (enterprise) {
                    require(foundationEnabled, "enterprise mappings require enterprise.foundation.enabled=true");
                    require(table.getCatalog() == null && table.getSchema() == null,
                            "enterprise mappings cannot select another catalog or schema");
                }
            }
        }
        for (var entity : metadata.getEntityBindings()) {
            boolean enterprise = EnterpriseSchemaFilterProvider.reserved(entity.getTable().getName());
            for (var table : entity.getTableClosure()) {
                require(enterprise == EnterpriseSchemaFilterProvider.reserved(table.getName()),
                        "entity tables cannot cross the enterprise schema boundary");
            }
            for (var join : entity.getJoinClosure()) {
                require(enterprise == EnterpriseSchemaFilterProvider.reserved(join.getTable().getName()),
                        "secondary tables cannot cross the enterprise schema boundary");
            }
            if (enterprise) {
                require(entity.getIdentifier() instanceof SimpleValue identifier
                                && "assigned".equals(identifier.getIdentifierGeneratorStrategy())
                                && identifier.getCustomIdGeneratorCreator() == null,
                        "enterprise identifiers must be assigned by the application");
            }
        }
        for (var namespace : metadata.getDatabase().getNamespaces()) {
            for (var table : namespace.getTables()) {
                for (var fk : table.getForeignKeys().values()) {
                    require(EnterpriseSchemaFilterProvider.reserved(table.getName())
                                    == EnterpriseSchemaFilterProvider.reserved(fk.getReferencedTable().getName()),
                            "foreign keys cannot cross the enterprise schema boundary");
                }
            }
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException("Enterprise JPA mapping rejected: " + reason);
        }
    }

    @Override
    public void disintegrate(SessionFactoryImplementor factory, SessionFactoryServiceRegistry registry) {
        // No per-factory state is retained.
    }
}
