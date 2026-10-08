package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.persistence.*;
import io.dataease.enterprise.tenant.domain.OrganizationHierarchy;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.enterprise.tenant.manage.OrganizationTransactionKernel;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Actual group management grants; neither member status nor a role name is authority. */
public final class ManagementAuthority implements OrganizationTransactionKernel.Authority {
    public static final Set<String> CAPABILITIES = Set.of("MANAGE_ORGANIZATIONS", "MANAGE_MEMBERS", "MANAGE_ROLES",
            "MANAGE_AUTHORIZATION", "MANAGE_EMBED_APPS", "MANAGE_DATASOURCE_BINDINGS", "INSTANTIATE_TEMPLATES");

    @Override public void requireManage(EntityManager em, AccessContext access) { require(em, access, "MANAGE_ORGANIZATIONS"); }
    @Override public void requireMappingRead(EntityManager em, AccessContext access) { requireManage(em, access); }

    public void require(EntityManager em, AccessContext access, String capability) {
        if (!allows(em, access, capability)) throw denied();
    }

    public boolean allows(EntityManager em, AccessContext access, String capability) {
        if (!CAPABILITIES.contains(capability)) throw new DEException(ResultCode.PARAM_IS_INVALID.code(), ResultCode.PARAM_IS_INVALID.message());
        if (!AccessContextHolder.requireCurrent().equals(access)) throw denied();
        if (em == null || !TransactionSynchronizationManager.isActualTransactionActive() || !em.getTransaction().isActive() || !TransactionSynchronizationManager.getResourceMap().values().stream().anyMatch(resource -> resource instanceof org.springframework.orm.jpa.EntityManagerHolder holder && holder.getEntityManager()==em))
            throw new IllegalStateException("Management decisions require the owning transaction");
        return evaluate(em,access,capability);
    }

    /** Internal invariant check, never binds or substitutes the request identity. */
    boolean remainsAdministrator(EntityManager em,long tenant,long user) {
        if (em == null || !TransactionSynchronizationManager.isActualTransactionActive() || !em.getTransaction().isActive() || !TransactionSynchronizationManager.getResourceMap().values().stream().anyMatch(resource -> resource instanceof org.springframework.orm.jpa.EntityManagerHolder holder && holder.getEntityManager()==em))
            throw new IllegalStateException("Administrator invariant requires the owning transaction");
        var facts=em.createQuery("select u.identityEpoch,t.accessEpoch from EnterpriseUser u,EnterpriseTenant t where u.id=:user and t.id=:tenant and u.status=:active and t.status=:active",Object[].class)
                .setParameter("user",user).setParameter("tenant",tenant).setParameter("active",FoundationStatus.ACTIVE).getResultList();
        return facts.size()==1 && evaluate(em,new AccessContext(tenant,user,(Long)facts.getFirst()[1],(Long)facts.getFirst()[0]),"MANAGE_AUTHORIZATION");
    }

    private boolean evaluate(EntityManager em,AccessContext access,String capability) {
        long valid = em.createQuery("SELECT COUNT(m) FROM EnterpriseTenantMember m, EnterpriseUser u, EnterpriseTenant t "
                        + "WHERE m.tenantId=:tenant AND m.userId=:user AND m.status=:active AND u.id=m.userId "
                        + "AND u.status=:active AND u.identityEpoch=:identity AND t.id=m.tenantId AND t.status=:active AND t.accessEpoch=:epoch", Long.class)
                .setParameter("tenant", access.tenantId()).setParameter("user", access.userId()).setParameter("active", FoundationStatus.ACTIVE)
                .setParameter("identity", access.identityEpoch()).setParameter("epoch", access.accessEpoch()).getSingleResult();
        if (valid != 1) return false;
        var member = em.createQuery("SELECT m FROM EnterpriseTenantMember m WHERE m.tenantId=:tenant AND m.userId=:user", EnterpriseTenantMember.class)
                .setParameter("tenant", access.tenantId()).setParameter("user", access.userId()).getSingleResult();
        // Restrict candidates in SQL; do not load every other member's grants for a group.
        var grants = em.createQuery("SELECT g FROM EnterpriseAdminGrant g, EnterpriseSubject s WHERE g.tenantId=:tenant "
                        + "AND s.tenantId=:tenant AND s.id=g.subjectId AND g.capability=:capability AND g.status=:active "
                        + "AND ((s.subjectType='USER' AND s.memberId=:member) OR "
                        + "(s.subjectType='ORG' AND s.orgId IN (SELECT m.orgId FROM EnterpriseOrgMember m WHERE m.tenantId=:tenant AND m.memberId=:member AND m.status=:active)) OR "
                        + "(s.subjectType='ROLE' AND s.roleId IN (SELECT a.roleId FROM EnterpriseRoleAssignment a WHERE a.tenantId=:tenant AND a.memberId=:member AND a.status=:active)))", EnterpriseAdminGrant.class)
                .setParameter("tenant", access.tenantId()).setParameter("member", member.getId())
                .setParameter("capability", capability).setParameter("active", FoundationStatus.ACTIVE).getResultList();
        Map<Long, Boolean> matches = new HashMap<>();
        boolean allow = false;
        for (var grant : grants) {
            boolean applies = matches.computeIfAbsent(grant.getSubjectId(), id -> matches(em, access.tenantId(), member.getId(), id));
            if (!applies) continue;
            if ("DENY".equals(grant.getEffect())) return false;
            if ("ALLOW".equals(grant.getEffect())) allow = true;
        }
        return allow;
    }

    private boolean matches(EntityManager em, long tenant, long member, long subjectId) {
        var subject = em.find(EnterpriseSubject.class, subjectId);
        if (subject == null || subject.getTenantId() != tenant) return false;
        return switch (subject.getSubjectType()) {
            case "USER" -> subject.getMemberId() != null && subject.getMemberId() == member;
            case "ORG" -> subject.getOrgId() != null && em.createQuery("SELECT COUNT(m) FROM EnterpriseOrgMember m "
                            + "WHERE m.tenantId=:tenant AND m.memberId=:member AND m.orgId=:org AND m.status=:active", Long.class)
                    .setParameter("tenant", tenant).setParameter("member", member).setParameter("org", subject.getOrgId())
                    .setParameter("active", FoundationStatus.ACTIVE).getSingleResult() == 1 && available(em, tenant, subject.getOrgId(), false);
            case "ROLE" -> subject.getRoleId() != null && role(em, tenant, member, subject.getRoleId());
            default -> false;
        };
    }

    private boolean role(EntityManager em, long tenant, long member, long roleId) {
        var role = em.find(EnterpriseRole.class, roleId);
        if (role == null || role.getTenantId() != tenant || role.getStatus() != FoundationStatus.ACTIVE) return false;
        var assignments = em.createQuery("SELECT a FROM EnterpriseRoleAssignment a WHERE a.tenantId=:tenant AND a.memberId=:member "
                        + "AND a.roleId=:role AND a.status=:active", EnterpriseRoleAssignment.class)
                .setParameter("tenant", tenant).setParameter("member", member).setParameter("role", roleId).setParameter("active", FoundationStatus.ACTIVE).getResultList();
        if (assignments.size() != 1) return false;
        var schools = em.createQuery("SELECT s.schoolId FROM EnterpriseAssignmentSchool s WHERE s.tenantId=:tenant AND s.assignmentId=:assignment", Long.class)
                .setParameter("tenant", tenant).setParameter("assignment", assignments.getFirst().getId()).getResultList();
        return !schools.isEmpty() && schools.stream().allMatch(id -> available(em, tenant, id, true));
    }

    private boolean available(EntityManager em, long tenant, long id, boolean school) {
        var entity = em.find(EnterpriseOrganization.class, id);
        if (entity == null || entity.getTenantId() != tenant || (school && entity.getKind() != EnterpriseOrganization.Kind.SCHOOL)) return false;
        try {
            new OrganizationHierarchy(128).requireAvailable(node(entity), other -> {
                var value = em.find(EnterpriseOrganization.class, other);
                return value == null || value.getTenantId() != tenant ? null : node(value);
            });
            return true;
        } catch (DEException unavailable) { return false; }
    }

    private OrganizationNode node(EnterpriseOrganization o) {
        return new OrganizationNode(o.getId(), o.getTenantId(), o.getParentId(), OrganizationNode.Kind.valueOf(o.getKind().name()),
                o.getName(), o.getSchoolCode(), o.getSchoolId(), OrganizationNode.Status.valueOf(o.getStatus().name()), o.getVersion());
    }

    private static DEException denied() { return new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message()); }
}
