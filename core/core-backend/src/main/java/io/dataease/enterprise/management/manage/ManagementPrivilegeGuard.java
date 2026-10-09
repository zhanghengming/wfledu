package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.persistence.EnterpriseOrganization;
import io.dataease.enterprise.tenant.manage.OrganizationTransactionKernel;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import jakarta.persistence.EntityManager;
import java.util.*;

/** Before/after effective capabilities under the owning group's mutation lock. */
public final class ManagementPrivilegeGuard {
    public record Snapshot(long tenantId, boolean authorized, Map<Long, Set<String>> capabilities) {
        public Snapshot { capabilities = Map.copyOf(capabilities); }
    }
    private final ManagementAuthority authority;
    public ManagementPrivilegeGuard(ManagementAuthority authority) { this.authority = authority; }

    public Snapshot capture(EntityManager em, long tenant, Collection<Long> users) {
        boolean authorized = authority.allows(em, AccessContextHolder.requireCurrent(), "MANAGE_AUTHORIZATION");
        if (authorized) return new Snapshot(tenant, true, Map.of());
        Map<Long, Set<String>> before = new HashMap<>();
        for (Long user : new HashSet<>(users)) before.put(user, authority.effectiveCapabilities(em, tenant, user));
        return new Snapshot(tenant, false, before);
    }

    public void verify(EntityManager em, Snapshot snapshot) {
        if (snapshot.authorized()) return;
        em.flush();
        em.clear();
        for (var entry : snapshot.capabilities().entrySet()) {
            if (!entry.getValue().equals(authority.effectiveCapabilities(em, snapshot.tenantId(), entry.getKey()))) {
                throw new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
            }
        }
    }

    public List<Long> roleUsers(EntityManager em, long tenant, long role) {
        return em.createQuery("select distinct m.userId from EnterpriseTenantMember m,EnterpriseRoleAssignment a "
                        + "where m.tenantId=:tenant and a.tenantId=:tenant and a.memberId=m.id and a.roleId=:role", Long.class)
                .setParameter("tenant", tenant).setParameter("role", role).getResultList();
    }

    public List<Long> organizationUsers(EntityManager em, long tenant, OrganizationTransactionKernel.Update command) {
        var target = em.find(EnterpriseOrganization.class, command.id());
        if (target == null || target.getTenantId() != tenant
                || Objects.equals(target.getParentId(), command.parentId())
                && target.getStatus().name().equals(command.status().name())) return List.of();
        // Reverse dependency closure includes both parent paths and explicit school references.
        var rows = em.createQuery("select o.id,o.parentId,o.schoolId from EnterpriseOrganization o where o.tenantId=:tenant", Object[].class)
                .setParameter("tenant", tenant).getResultList();
        Map<Long, Set<Long>> children = new HashMap<>();
        for (var row : rows) for (int reference = 1; reference <= 2; reference++)
            if (row[reference] != null) children.computeIfAbsent((Long) row[reference], ignored -> new HashSet<>()).add((Long) row[0]);
        Set<Long> affected = new HashSet<>();
        var frontier = new ArrayList<Long>(); frontier.add(command.id());
        for (int level = 0; !frontier.isEmpty(); level++) {
            if (level >= 128) throw new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
            var next = new ArrayList<Long>();
            for (Long id : frontier) {
                if (!affected.add(id)) continue; // A department may refer to the same school through both edges.
                next.addAll(children.getOrDefault(id, Set.of()));
            }
            frontier = next;
        }
        Set<Long> users = new HashSet<>();
        var ids = new ArrayList<>(affected);
        for (int start = 0; start < ids.size(); start += 100) {
            var page = ids.subList(start, Math.min(start + 100, ids.size()));
            users.addAll(em.createQuery("select distinct m.userId from EnterpriseTenantMember m,EnterpriseOrgMember o "
                            + "where m.tenantId=:tenant and o.tenantId=:tenant and o.memberId=m.id and o.orgId in :ids", Long.class)
                    .setParameter("tenant", tenant).setParameter("ids", page).getResultList());
            users.addAll(em.createQuery("select distinct m.userId from EnterpriseTenantMember m,EnterpriseRoleAssignment a,EnterpriseAssignmentSchool s "
                            + "where m.tenantId=:tenant and a.tenantId=:tenant and s.tenantId=:tenant and a.memberId=m.id "
                            + "and s.assignmentId=a.id and s.schoolId in :ids", Long.class)
                    .setParameter("tenant", tenant).setParameter("ids", page).getResultList());
        }
        return List.copyOf(users);
    }
}
