package io.dataease.enterprise.permission.manage;

import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.persistence.*;
import io.dataease.enterprise.permission.persistence.*;
import io.dataease.enterprise.permission.domain.PermissionDecision.*;
import io.dataease.enterprise.tenant.domain.OrganizationHierarchy;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Detached immutable facts from the caller's short, tenant-locked transaction; no decision cache. */
public final class PermissionFactLoader {
    private final EntityManagerFactory factory;
    public PermissionFactLoader(EntityManagerFactory factory) { this.factory = factory; }
    public Facts load(EntityManager em, EnterpriseTenant tenant, long userId, String type, long resourceId) {
        return load(em, tenant, userId, type, resourceId, false);
    }
    public Facts loadForCurrentUser(EntityManager em, EnterpriseTenant tenant, String type, long resourceId) {
        return load(em, tenant, io.dataease.enterprise.context.AccessContextHolder.requireCurrent().userId(), type, resourceId, true);
    }
    private Facts load(EntityManager em, EnterpriseTenant tenant, long userId, String type, long resourceId, boolean currentUser) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !em.contains(tenant) || !Set.of(LockModeType.PESSIMISTIC_READ, LockModeType.PESSIMISTIC_WRITE).contains(em.getLockMode(tenant))
                || em != org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(factory)
                || io.dataease.enterprise.context.AccessContextHolder.requireCurrent().tenantId() != tenant.getId()) throw new IllegalStateException("Facts require owning metadata transaction");
        var user = em.find(EnterpriseUser.class, userId, LockModeType.PESSIMISTIC_READ);
        if (user == null || user.getStatus() != FoundationStatus.ACTIVE) throw PermissionReferences.missing();
        var owner = PermissionReferences.resource(em, tenant.getId(), type, resourceId);
        var members = em.createQuery("from EnterpriseTenantMember where tenantId=:tenant and userId=:user", EnterpriseTenantMember.class)
                .setParameter("tenant", tenant.getId()).setParameter("user", userId).getResultList();
        boolean platformView = ManagementSessionService.qualified(em, userId, "GROUP_READ_ALL");
        if (members.isEmpty() && !platformView && !currentUser) throw PermissionReferences.missing();
        boolean memberActive = members.size() == 1 && members.getFirst().getStatus() == FoundationStatus.ACTIVE;
        Long member = memberActive ? members.getFirst().getId() : null;
        var orgs = member == null ? List.<Long>of() : bounded(em.createQuery("select o.orgId from EnterpriseOrgMember o where o.tenantId=:tenant and o.memberId=:member and o.status=:active", Long.class)
                .setParameter("tenant", tenant.getId()).setParameter("member", member).setParameter("active", FoundationStatus.ACTIVE).setMaxResults(1001).getResultList(), 1000);
        var assignments = member == null ? List.<EnterpriseRoleAssignment>of() : bounded(em.createQuery("select a from EnterpriseRoleAssignment a,EnterpriseRole r where a.tenantId=:tenant and r.tenantId=:tenant and a.memberId=:member and a.roleId=r.id and a.status=:active and r.status=:active", EnterpriseRoleAssignment.class)
                .setParameter("tenant", tenant.getId()).setParameter("member", member).setParameter("active", FoundationStatus.ACTIVE).setMaxResults(1001).getResultList(), 1000);
        var assignmentIds = assignments.stream().map(EnterpriseRoleAssignment::getId).toList();
        Map<Long, Set<Long>> assignedSchools = new HashMap<>();
        if (!assignmentIds.isEmpty()) for (var row : bounded(em.createQuery("select a.assignmentId,a.schoolId from EnterpriseAssignmentSchool a where a.tenantId=:tenant and a.assignmentId in :ids", Object[].class)
                .setParameter("tenant", tenant.getId()).setParameter("ids", assignmentIds).setMaxResults(5001).getResultList(), 5000))
            assignedSchools.computeIfAbsent((Long) row[0], ignored -> new HashSet<>()).add((Long) row[1]);
        var schools = bounded(em.createQuery("from EnterpriseOrganization where tenantId=:tenant and kind=:kind", EnterpriseOrganization.class)
                .setParameter("tenant", tenant.getId()).setParameter("kind", EnterpriseOrganization.Kind.SCHOOL).setMaxResults(5001).getResultList(), 5000);
        Map<Long, OrganizationNode> topology = new HashMap<>(); Set<Long> frontier = new HashSet<>(orgs);
        schools.forEach(o -> { topology.put(o.getId(), node(o)); frontier.add(o.getId()); });
        assignedSchools.values().forEach(frontier::addAll);
        for (int depth = 0; !frontier.isEmpty(); depth++) {
            if (depth >= 128 || topology.size() > 10000) throw denied();
            var ids = new ArrayList<>(frontier); frontier.clear();
            for (int start = 0; start < ids.size(); start += 500) {
                var page = ids.subList(start, Math.min(start + 500, ids.size()));
                var rows = em.createQuery("from EnterpriseOrganization where tenantId=:tenant and id in :ids", EnterpriseOrganization.class)
                        .setParameter("tenant", tenant.getId()).setParameter("ids", page).getResultList();
                for (var o : rows) {
                    topology.put(o.getId(), node(o));
                    if (o.getParentId() != null && !topology.containsKey(o.getParentId())) frontier.add(o.getParentId());
                    if (o.getSchoolId() != null && !topology.containsKey(o.getSchoolId())) frontier.add(o.getSchoolId());
                }
            }
        }
        if (topology.size() > 10000) throw denied();
        Set<Long> availableSchools = new HashSet<>();
        for (var school : schools) if (available(topology, school.getId())) availableSchools.add(school.getId());
        var roles = assignments.stream().map(EnterpriseRoleAssignment::getRoleId).toList();
        var subjects = member == null ? List.<EnterpriseSubject>of() : bounded(em.createQuery("from EnterpriseSubject s where s.tenantId=:tenant and (s.subjectType='USER' and s.memberId=:member or s.subjectType='ORG' and s.orgId in :orgs or s.subjectType='ROLE' and s.roleId in :roles)", EnterpriseSubject.class)
                .setParameter("tenant", tenant.getId()).setParameter("member", member)
                .setParameter("orgs", orgs.isEmpty() ? List.of(-1L) : orgs).setParameter("roles", roles.isEmpty() ? List.of(-1L) : roles).setMaxResults(3001).getResultList(), 3000);
        Map<Long, List<Rule>> rules = new HashMap<>();
        if (!subjects.isEmpty()) {
            var grants = bounded(em.createQuery("from EnterpriseGrant g where g.tenantId=:tenant and g.subjectId in :subjects and g.status=:active and g.policyKind=:policy and g.resourceType=:type and (g.resourceScopeKind='EXACT' and g.resourceId=:resource or g.resourceScopeKind='ALL_DATASETS_IN_TENANT' and g.resourceId is null)", EnterpriseGrant.class)
                    .setParameter("tenant", tenant.getId()).setParameter("subjects", subjects.stream().map(EnterpriseSubject::getId).toList())
                    .setParameter("active", FoundationStatus.ACTIVE).setParameter("policy", "DATASET".equals(type) ? "DATA_ACCESS" : "RESOURCE_ACTION")
                    .setParameter("type", PermissionCommands.storageType(type)).setParameter("resource", resourceId).setMaxResults(1001).getResultList(), 1000);
            Map<Long, Set<Long>> grantSchools = new HashMap<>();
            if (!grants.isEmpty()) for (var row : bounded(em.createQuery("select s.grantId,s.schoolId from EnterpriseGrantSchool s where s.tenantId=:tenant and s.grantId in :grants", Object[].class)
                    .setParameter("tenant", tenant.getId()).setParameter("grants", grants.stream().map(EnterpriseGrant::getId).toList()).setMaxResults(10001).getResultList(), 10000))
                grantSchools.computeIfAbsent((Long) row[0], ignored -> new HashSet<>()).add((Long) row[1]);
            for (var grant : grants) rules.computeIfAbsent(grant.getSubjectId(), ignored -> new ArrayList<>()).add(new Rule(grant.getId(), grant.getVersion(), grant.getAction(), grant.getEffect(), grant.getSchoolScopeKind(), grantSchools.getOrDefault(grant.getId(), Set.of())));
        }
        List<Binding> bindings = new ArrayList<>();
        for (var subject : subjects) {
            var subjectRules = rules.getOrDefault(subject.getId(), List.of());
            switch (subject.getSubjectType()) {
                case "USER" -> bindings.add(new Binding("USER", userId, null, Set.of(), subjectRules));
                case "ORG" -> { if (available(topology, subject.getOrgId())) bindings.add(new Binding("ORG", subject.getOrgId(), null, Set.of(), subjectRules)); }
                case "ROLE" -> { for (var a : assignments) if (a.getRoleId().equals(subject.getRoleId())) bindings.add(new Binding("ROLE", a.getRoleId(), a.getId(), assignedSchools.getOrDefault(a.getId(), Set.of()), subjectRules)); }
                default -> throw denied();
            }
        }
        return new Facts(tenant.getId(), userId, user.getIdentityEpoch(), tenant.getAccessEpoch(), resourceId, owner.getVersion(), type,
                owner.getSchoolId(), memberActive, platformView, availableSchools, bindings);
    }
    private static boolean available(Map<Long, OrganizationNode> nodes, Long id) {
        var node = nodes.get(id); if (node == null) return false;
        try { new OrganizationHierarchy(128).requireAvailable(node, nodes::get); return true; }
        catch (DEException unavailable) { return false; }
    }
    private static OrganizationNode node(EnterpriseOrganization o) { return new OrganizationNode(o.getId(), o.getTenantId(), o.getParentId(), OrganizationNode.Kind.valueOf(o.getKind().name()), o.getName(), o.getSchoolCode(), o.getSchoolId(), OrganizationNode.Status.valueOf(o.getStatus().name()), o.getVersion()); }
    private static <T> List<T> bounded(List<T> rows, int maximum) { if (rows.size() > maximum) throw denied(); return rows; }
    private static DEException denied() { return new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message()); }
}
