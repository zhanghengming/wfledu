package io.dataease.enterprise.tenant.manage;

import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.persistence.EnterpriseOrganization;
import io.dataease.enterprise.identity.persistence.EnterpriseTenant;
import io.dataease.enterprise.identity.persistence.EnterpriseUser;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.enterprise.tenant.domain.OrganizationHierarchy;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Internal transaction root. Intentionally not a Spring component or HTTP endpoint.
 * Production wiring requires reviewed authority and same-transaction audit implementations.
 */
public final class OrganizationTransactionKernel {
    public record Create(OrganizationNode.Kind kind, String name, String schoolCode, Long parentId,
                         Long schoolId, OrganizationNode.Status status) { }
    /** Full mutable configuration. Kind, school code, school ownership and tenant cannot be replaced. */
    public record Update(long id, long expectedVersion, String name, Long parentId, OrganizationNode.Status status) { }
    public record Mutation(long id, long version, long accessEpoch) { }
    public record SchoolReference(long tenantId, long schoolId, String schoolCode, long version, long accessEpoch) { }
    public record Change(long tenantId, long organizationId, long actorId, long version, long accessEpoch,
                         String operation, LocalDateTime occurredAt) { }

    public interface Authority {
        void requireManage(EntityManager transaction, AccessContext access);
        void requireMappingRead(EntityManager transaction, AccessContext access);
    }
    @FunctionalInterface
    public interface Audit {
        /** Must append using this EntityManager, without independent transactions or after-commit callbacks. */
        void append(EntityManager transaction, Change change);
    }

    private final EntityManagerFactory factory;
    private final TransactionTemplate transactions;
    private final Authority authority;
    private final Audit audit;
    private final Clock clock;
    private final OrganizationHierarchy hierarchy;

    public OrganizationTransactionKernel(EntityManagerFactory factory, JpaTransactionManager manager,
                                         Authority authority, Audit audit, Clock clock, int maximumDepth) {
        this.factory = Objects.requireNonNull(factory);
        if (Objects.requireNonNull(manager).getEntityManagerFactory() != factory) {
            throw new IllegalArgumentException("Organization transactions must use the same EntityManagerFactory");
        }
        this.authority = Objects.requireNonNull(authority, "A real authority implementation is required");
        this.audit = Objects.requireNonNull(audit, "A same-transaction audit implementation is required");
        this.clock = Objects.requireNonNull(clock);
        this.hierarchy = new OrganizationHierarchy(maximumDepth);
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(10);
    }

    public Mutation create(Create command) {
        Objects.requireNonNull(command);
        return command((em, tenant) -> {
            var access = AccessContextHolder.requireCurrent();
            long id = IDUtils.snowID();
            var candidate = new OrganizationNode(id, access.tenantId(), command.parentId(), command.kind(), command.name(),
                    command.schoolCode(), command.schoolId(), command.status(), 1);
            validate(em, candidate);
            var now = now();
            var entity = new EnterpriseOrganization();
            entity.setId(id);
            entity.setTenantId(access.tenantId());
            entity.setKind(EnterpriseOrganization.Kind.valueOf(candidate.kind().name()));
            entity.setName(candidate.name());
            entity.setSchoolCode(candidate.schoolCode());
            entity.setSchoolId(candidate.schoolId());
            entity.setParentId(candidate.parentId());
            entity.setStatus(FoundationStatus.valueOf(candidate.status().name()));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entity.setCreatedBy(access.userId());
            entity.setUpdatedBy(access.userId());
            em.persist(entity);
            try {
                em.flush();
            } catch (RuntimeException failure) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())) {
                    throw error(ResultCode.DATA_ALREADY_EXISTED);
                }
                throw failure;
            }
            return finish(em, tenant, candidate.id(), 1, "CREATE", now);
        });
    }

    public Mutation update(Update command) {
        Objects.requireNonNull(command);
        if (command.id() <= 0 || command.expectedVersion() <= 0) throw error(ResultCode.PARAM_IS_INVALID);
        return command((em, tenant) -> {
            var access = AccessContextHolder.requireCurrent();
            var previous = requireNode(em, access.tenantId(), command.id());
            if (previous.version() != command.expectedVersion() || previous.version() == Long.MAX_VALUE) {
                throw error(ResultCode.DATA_IS_WRONG);
            }
            var candidate = new OrganizationNode(previous.id(), previous.tenantId(), command.parentId(), previous.kind(),
                    command.name(), previous.schoolCode(), previous.schoolId(), command.status(), previous.version());
            validate(em, candidate);
            var now = now();
            int rows = em.createQuery("UPDATE EnterpriseOrganization o SET o.name=:name,o.parentId=:parent,o.status=:status,"
                            + "o.version=o.version+1,o.updatedAt=:time,o.updatedBy=:actor "
                            + "WHERE o.tenantId=:tenant AND o.id=:id AND o.version=:version")
                    .setParameter("name", candidate.name()).setParameter("parent", candidate.parentId())
                    .setParameter("status", FoundationStatus.valueOf(candidate.status().name()))
                    .setParameter("time", now).setParameter("actor", access.userId())
                    .setParameter("tenant", access.tenantId()).setParameter("id", candidate.id())
                    .setParameter("version", command.expectedVersion()).executeUpdate();
            if (rows != 1) throw error(ResultCode.DATA_IS_WRONG);
            return finish(em, tenant, candidate.id(), previous.version() + 1, "UPDATE", now);
        });
    }

    public SchoolReference resolveSchoolCode(String code) {
        return read((em, tenant) -> {
            OrganizationHierarchy.requireText(code, 64);
            var nodes = em.createQuery("SELECT o FROM EnterpriseOrganization o WHERE o.tenantId=:tenant AND o.schoolCode=:code",
                            EnterpriseOrganization.class)
                    .setParameter("tenant", tenant.getId()).setParameter("code", code).setMaxResults(1).getResultList();
            if (nodes.isEmpty()) throw error(ResultCode.RESOURCE_NOT_EXIST);
            return school(em, tenant, node(nodes.getFirst()));
        });
    }

    public SchoolReference resolveSchoolId(long id) {
        return read((em, tenant) -> school(em, tenant, requireNode(em, tenant.getId(), id)));
    }

    private SchoolReference school(EntityManager em, EnterpriseTenant tenant, OrganizationNode school) {
        if (school.kind() != OrganizationNode.Kind.SCHOOL) throw error(ResultCode.RESOURCE_NOT_EXIST);
        hierarchy.requireAvailable(school, loader(em, tenant.getId()));
        return new SchoolReference(tenant.getId(), school.id(), school.schoolCode(), school.version(), tenant.getAccessEpoch());
    }

    private void validate(EntityManager em, OrganizationNode candidate) {
        var loader = loader(em, candidate.tenantId());
        hierarchy.validate(candidate, loader);
        if (candidate.status() == OrganizationNode.Status.ACTIVE) hierarchy.requireAvailable(candidate, loader);
    }

    private Mutation finish(EntityManager em, EnterpriseTenant tenant, long id, long version, String operation, LocalDateTime time) {
        var access = AccessContextHolder.requireCurrent();
        if (tenant.getAccessEpoch() == Long.MAX_VALUE || tenant.getVersion() == Long.MAX_VALUE) throw error(ResultCode.DATA_IS_WRONG);
        long epoch = tenant.getAccessEpoch() + 1;
        int rows = em.createQuery("UPDATE EnterpriseTenant t SET t.accessEpoch=t.accessEpoch+1,t.version=t.version+1,"
                        + "t.updatedAt=:time,t.updatedBy=:actor WHERE t.id=:tenant AND t.version=:version AND t.accessEpoch=:epoch")
                .setParameter("time", time).setParameter("actor", access.userId()).setParameter("tenant", access.tenantId())
                .setParameter("version", tenant.getVersion()).setParameter("epoch", tenant.getAccessEpoch()).executeUpdate();
        if (rows != 1) throw error(ResultCode.DATA_IS_WRONG);
        audit.append(em, new Change(access.tenantId(), id, access.userId(), version, epoch, operation, time));
        em.flush();
        em.clear();
        var fresh = requireNode(em, access.tenantId(), id);
        if (fresh.version() != version) throw error(ResultCode.DATA_IS_WRONG);
        return new Mutation(id, fresh.version(), epoch);
    }

    private <T> T command(BiFunction<EntityManager, EnterpriseTenant, T> operation) {
        return execute(true, operation);
    }

    private <T> T read(BiFunction<EntityManager, EnterpriseTenant, T> operation) {
        return execute(false, operation);
    }

    private <T> T execute(boolean write, BiFunction<EntityManager, EnterpriseTenant, T> operation) {
        var access = AccessContextHolder.requireCurrent();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Organization operations must own their transaction");
        }
        return transactions.execute(status -> {
            var em = Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory));
            // Also protects against stale entities retained by an open-in-view persistence context.
            em.clear();
            var user = em.find(EnterpriseUser.class, access.userId(), LockModeType.PESSIMISTIC_READ);
            if (user == null || user.getStatus() != FoundationStatus.ACTIVE || user.getIdentityEpoch() != access.identityEpoch()) {
                throw error(ResultCode.PERMISSION_NO_ACCESS);
            }
            var tenant = em.find(EnterpriseTenant.class, access.tenantId(), write ? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ);
            if (tenant == null || tenant.getStatus() != FoundationStatus.ACTIVE || tenant.getAccessEpoch() != access.accessEpoch()) {
                throw error(ResultCode.PERMISSION_NO_ACCESS);
            }
            if (write) authority.requireManage(em, access); else authority.requireMappingRead(em, access);
            return operation.apply(em, tenant);
        });
    }

    private static Function<Long, OrganizationNode> loader(EntityManager em, long tenant) {
        return id -> findNode(em, tenant, id);
    }

    private static OrganizationNode requireNode(EntityManager em, long tenant, long id) {
        var result = findNode(em, tenant, id);
        if (result == null) throw error(ResultCode.RESOURCE_NOT_EXIST);
        return result;
    }

    private static OrganizationNode findNode(EntityManager em, long tenant, long id) {
        if (id <= 0) throw error(ResultCode.PARAM_IS_INVALID);
        var result = em.createQuery("SELECT o FROM EnterpriseOrganization o WHERE o.tenantId=:tenant AND o.id=:id", EnterpriseOrganization.class)
                .setParameter("tenant", tenant).setParameter("id", id).setMaxResults(1).getResultList();
        return result.isEmpty() ? null : node(result.getFirst());
    }

    private static OrganizationNode node(EnterpriseOrganization entity) {
        return new OrganizationNode(entity.getId(), entity.getTenantId(), entity.getParentId(),
                OrganizationNode.Kind.valueOf(entity.getKind().name()), entity.getName(), entity.getSchoolCode(), entity.getSchoolId(),
                OrganizationNode.Status.valueOf(entity.getStatus().name()), entity.getVersion());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static DEException error(ResultCode code) { return new DEException(code.code(), code.message()); }
}
