package io.dataease.enterprise.audit.manage;

import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.enterprise.tenant.manage.OrganizationTransactionKernel;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.function.Supplier;

/** Explicit same-transaction adapter. No default bean, independent transaction, or arbitrary payload. */
public final class OrganizationAuditAppender implements OrganizationTransactionKernel.Audit {
    private final EntityManagerFactory factory;
    private final Supplier<String> trace;

    public OrganizationAuditAppender(EntityManagerFactory factory, Supplier<String> trustedTrace) {
        this.factory = Objects.requireNonNull(factory);
        this.trace = Objects.requireNonNull(trustedTrace);
    }

    @Override
    public void append(EntityManager transaction, OrganizationTransactionKernel.Change change) {
        var access = AccessContextHolder.requireCurrent();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || transaction == null || transaction != EntityManagerFactoryUtils.getTransactionalEntityManager(factory)
                || !transaction.getTransaction().isActive()) {
            throw new IllegalStateException("Organization audit must join the owning transaction and factory");
        }
        // Spring exposes a factory proxy while Hibernate may return its native factory. The exact
        // EntityManager bound to this factory's transaction resource is the ownership check.
        if (change == null || change.tenantId() != access.tenantId() || change.actorId() != access.userId()
                || change.organizationId() <= 0 || change.version() <= 0 || change.occurredAt() == null
                || access.accessEpoch() == Long.MAX_VALUE || change.accessEpoch() != access.accessEpoch() + 1) {
            throw new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
        }
        String event = switch (change.operation() == null ? "" : change.operation()) {
            case "CREATE" -> "ORGANIZATION_CREATED";
            case "UPDATE" -> "ORGANIZATION_UPDATED";
            default -> throw new DEException(ResultCode.PARAM_IS_INVALID.code(), ResultCode.PARAM_IS_INVALID.message());
        };
        String traceId = trace.get();
        if (traceId == null || !traceId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new DEException(ResultCode.PARAM_IS_INVALID.code(), ResultCode.PARAM_IS_INVALID.message());
        }
        // Scalar reads see bulk CAS writes; a shape-valid context/change is not proof of a committed mutation.
        long matches = transaction.createQuery("SELECT COUNT(o) FROM EnterpriseOrganization o, EnterpriseTenant t, EnterpriseUser u "
                        + "WHERE o.id=:org AND o.tenantId=:tenant AND o.version=:version "
                        + "AND t.id=:tenant AND t.status=:active AND t.accessEpoch=:epoch "
                        + "AND u.id=:actor AND u.status=:active AND u.identityEpoch=:identity", Long.class)
                .setParameter("org", change.organizationId()).setParameter("tenant", change.tenantId())
                .setParameter("version", change.version()).setParameter("epoch", change.accessEpoch())
                .setParameter("actor", change.actorId()).setParameter("identity", access.identityEpoch())
                .setParameter("active", FoundationStatus.ACTIVE).getSingleResult();
        if (matches != 1) throw new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
        long id = IDUtils.snowID();
        if (id <= 0) throw new IllegalStateException("Audit ID must be positive");
        transaction.persist(new EnterpriseAuditEvent(id, change.occurredAt(), change.tenantId(), change.actorId(), event,
                change.organizationId(), traceId, change.accessEpoch(), change.version()));
    }
}
