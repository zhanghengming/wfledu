package io.dataease.enterprise.permission.manage;

import io.dataease.api.permissions.enterprise.PermissionContract.*;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.manage.ManagementTransactions;
import io.dataease.enterprise.management.persistence.*;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.enterprise.permission.persistence.*;
import io.dataease.result.PageResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.PageRequest;
import java.util.*;

/** Configuration metadata only: this service neither evaluates data permissions nor fetches business data. */
public final class PermissionReadService {
    private final ManagementTransactions transactions;
    public PermissionReadService(ManagementTransactions transactions) { this.transactions = transactions; }
    public Map<String, Object> catalog(Principal principal, Catalog input) {
        if (input == null) throw ManagementFields.invalid();
        PermissionCommands.subject(input.subject()); PermissionCommands.one(input.resourceType(), PermissionCommands.TYPES);
        int page = ManagementFields.page(input.pageNum()), size = ManagementFields.size(input.pageSize());
        if (input.keyword() != null) ManagementFields.text(input.keyword(), 128);
        Long school = ManagementFields.optionalId(input.schoolId());
        return transactions.group(principal, "MANAGE_AUTHORIZATION", false, (em, tenant) -> {
            epoch(tenant, input.expectedEpoch(), page);
            PermissionReferences.subject(em, tenant.getId(), input.subject(), false);
            if (school != null) PermissionReferences.organizations(em, tenant.getId(), Set.of(school), true);
            String pattern = input.keyword() == null ? null : "%" + input.keyword().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            String schoolWhere = " where o.tenantId=:tenant and o.kind=:kind" + (school == null ? "" : " and o.id=:school")
                    + (pattern == null ? "" : " and o.name like :keyword escape '!'");
            var sq = em.createQuery("select o from EnterpriseOrganization o" + schoolWhere + " order by o.id", EnterpriseOrganization.class);
            var sc = em.createQuery("select count(o) from EnterpriseOrganization o" + schoolWhere, Long.class);
            for (var q : List.of(sq, sc)) {
                q.setParameter("tenant", tenant.getId()).setParameter("kind", EnterpriseOrganization.Kind.SCHOOL);
                if (school != null) q.setParameter("school", school);
                if (pattern != null) q.setParameter("keyword", pattern);
            }
            var schools = sq.setFirstResult((page - 1) * size).setMaxResults(size).getResultList().stream()
                    .map(o -> Map.<String, Object>of("id", o.getId().toString(), "name", o.getName(), "code", o.getSchoolCode(), "status", o.getStatus().name())).toList();
            String nativeTable = "DATASET".equals(input.resourceType()) ? "CoreDatasetGroup" : "DataVisualizationInfo";
            String where = " where r.tenantId=:tenant and r.resourceType=:type and r.resourceKind=:resourceKind and n.id=r.id";
            where += "DATASET".equals(input.resourceType()) ? " and n.nodeType='dataset'" : " and n.nodeType='panel' and n.type='dashboard' and n.orgId=:tenant and n.deleteFlag=false";
            if (school != null && "SCHOOL_COPY".equals(input.resourceType())) where += " and r.schoolId=:school";
            if (pattern != null) where += " and n.name like :keyword escape '!'";
            var rq = em.createQuery("select r,n.name from EnterpriseResource r," + nativeTable + " n" + where + " order by r.id", Object[].class);
            var rc = em.createQuery("select count(r) from EnterpriseResource r," + nativeTable + " n" + where, Long.class);
            for (var q : List.of(rq, rc)) {
                q.setParameter("tenant", tenant.getId()).setParameter("type", PermissionCommands.storageType(input.resourceType()))
                        .setParameter("resourceKind", PermissionCommands.resourceKind(input.resourceType()));
                if (school != null && "SCHOOL_COPY".equals(input.resourceType())) q.setParameter("school", school);
                if (pattern != null) q.setParameter("keyword", pattern);
            }
            var resources = rq.setFirstResult((page - 1) * size).setMaxResults(size).getResultList().stream().map(v -> {
                var r = (EnterpriseResource) v[0];
                var map = new LinkedHashMap<String, Object>(); map.put("id", r.getId().toString()); map.put("name", v[1]);
                map.put("resourceType", input.resourceType()); map.put("status", r.getStatus().name());
                map.put("schoolId", r.getSchoolId() == null ? null : r.getSchoolId().toString()); return (Map<String, Object>) map;
            }).toList();
            return Map.of("subject", input.subject(), "accessEpoch", tenant.getAccessEpoch().toString(),
                    "schools", page(schools, sc.getSingleResult(), page, size), "resources", page(resources, rc.getSingleResult(), page, size));
        });
    }
    public Map<String, Object> capabilities(Principal principal, CapabilitiesPage input) {
        if (input == null) throw ManagementFields.invalid();
        PermissionCommands.subject(input.subject());
        int page = ManagementFields.page(input.pageNum()), size = ManagementFields.size(input.pageSize());
        return transactions.group(principal, "MANAGE_AUTHORIZATION", false, (em, tenant) -> {
            epoch(tenant, input.expectedEpoch(), page);
            var target = PermissionReferences.subject(em, tenant.getId(), input.subject(), false);
            if (target.subjectId() == null) return Map.of("accessEpoch", tenant.getAccessEpoch().toString(), "subject", input.subject(), "grants", page(List.of(), 0, page, size));
            var query = em.createQuery("from EnterpriseAdminGrant where tenantId=:tenant and subjectId=:subject order by id", EnterpriseAdminGrant.class)
                    .setParameter("tenant", tenant.getId()).setParameter("subject", target.subjectId());
            var records = query.setFirstResult((page - 1) * size).setMaxResults(size).getResultList().stream().map(g -> Map.<String, Object>of(
                    "id", g.getId().toString(), "version", g.getVersion().toString(), "capability", g.getCapability(), "effect", g.getEffect(), "status", g.getStatus().name())).toList();
            long total = em.createQuery("select count(g) from EnterpriseAdminGrant g where g.tenantId=:tenant and g.subjectId=:subject", Long.class)
                    .setParameter("tenant", tenant.getId()).setParameter("subject", target.subjectId()).getSingleResult();
            return Map.of("accessEpoch", tenant.getAccessEpoch().toString(), "subject", input.subject(), "grants", page(records, total, page, size));
        });
    }
    public Map<String, Object> rules(Principal principal, RulesPage input) {
        if (input == null) throw ManagementFields.invalid();
        PermissionCommands.subject(input.subject());
        if (input.grantId() != null) return schools(principal, input);
        if (input.expectedVersion() != null || input.schoolsPageNum() != null || input.schoolsPageSize() != null) throw ManagementFields.invalid();
        if (input.policyKind() != null) PermissionCommands.one(input.policyKind(), Set.of("DATA_ACCESS", "RESOURCE_ACTION"));
        if (input.resourceType() != null) PermissionCommands.one(input.resourceType(), PermissionCommands.TYPES);
        if (input.action() != null) PermissionCommands.one(input.action(), PermissionCommands.ACTIONS);
        Long resource = ManagementFields.optionalId(input.resourceId());
        if (resource != null && input.resourceType() == null) throw ManagementFields.invalid();
        int page = ManagementFields.page(input.pageNum()), size = ManagementFields.size(input.pageSize());
        return transactions.group(principal, "MANAGE_AUTHORIZATION", false, (em, tenant) -> {
            epoch(tenant, input.expectedEpoch(), page);
            var target = PermissionReferences.subject(em, tenant.getId(), input.subject(), false);
            if (resource != null) PermissionReferences.resource(em, tenant.getId(), input.resourceType(), resource);
            if (target.subjectId() == null) return Map.of("accessEpoch", tenant.getAccessEpoch().toString(), "subject", input.subject(), "rules", page(List.of(), 0, page, size));
            String where = " where g.tenantId=:tenant and g.subjectId=:subject";
            var params = new LinkedHashMap<String, Object>(); params.put("tenant", tenant.getId()); params.put("subject", target.subjectId());
            if (input.policyKind() != null) { where += " and g.policyKind=:policy"; params.put("policy", input.policyKind()); }
            if (input.action() != null) { where += " and g.action=:action"; params.put("action", input.action()); }
            if (resource != null) { where += " and g.resourceId=:resource"; params.put("resource", resource); }
            if (input.resourceType() != null) {
                where += " and g.resourceType=:type"; params.put("type", PermissionCommands.storageType(input.resourceType()));
                if (!"DATASET".equals(input.resourceType())) {
                    where += " and exists(select r.id from EnterpriseResource r where r.tenantId=:tenant and r.id=g.resourceId and r.resourceKind=:kind)";
                    params.put("kind", PermissionCommands.resourceKind(input.resourceType()));
                }
            }
            var query = em.createQuery("select g from EnterpriseGrant g" + where + " order by g.id", EnterpriseGrant.class);
            var count = em.createQuery("select count(g) from EnterpriseGrant g" + where, Long.class);
            for (var q : List.of(query, count)) params.forEach(q::setParameter);
            var records = query.setFirstResult((page - 1) * size).setMaxResults(size).getResultList().stream().map(g -> projection(em, tenant.getId(), g)).toList();
            return Map.of("accessEpoch", tenant.getAccessEpoch().toString(), "subject", input.subject(), "rules", page(records, count.getSingleResult(), page, size));
        });
    }
    private Map<String, Object> schools(Principal principal, RulesPage input) {
        if (input.expectedVersion() == null || input.expectedEpoch() == null || input.policyKind() != null || input.resourceType() != null
                || input.resourceId() != null || input.action() != null || input.pageNum() != null || input.pageSize() != null) throw ManagementFields.invalid();
        long id = ManagementFields.id(input.grantId()); ManagementFields.id(input.expectedVersion());
        int page = ManagementFields.page(input.schoolsPageNum()), size = ManagementFields.size(input.schoolsPageSize());
        return transactions.group(principal, "MANAGE_AUTHORIZATION", false, (em, tenant) -> {
            epoch(tenant, input.expectedEpoch(), page);
            var target = PermissionReferences.subject(em, tenant.getId(), input.subject(), false);
            var grant = em.find(EnterpriseGrant.class, id);
            if (grant == null || !Objects.equals(grant.getTenantId(), tenant.getId()) || !Objects.equals(grant.getSubjectId(), target.subjectId())) throw PermissionReferences.missing();
            PermissionReferences.version(grant.getVersion(), input.expectedVersion());
            if (!"EXPLICIT".equals(grant.getSchoolScopeKind())) throw ManagementFields.invalid();
            var ids = schoolIds(em, tenant.getId(), id, page, size); long total = schoolTotal(em, tenant.getId(), id);
            return Map.of("accessEpoch", tenant.getAccessEpoch().toString(), "grantId", input.grantId(), "version", grant.getVersion().toString(),
                    "schools", page(ids, total, page, size), "complete", ids.size() == total,
                    "hasNext", (long) (page - 1) * size + ids.size() < total);
        });
    }
    private Map<String, Object> projection(EntityManager em, long tenant, EnterpriseGrant g) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", g.getId().toString()); row.put("version", g.getVersion().toString()); row.put("policyKind", g.getPolicyKind());
        String type = g.getResourceType();
        if ("DASHBOARD".equals(type)) {
            var owner = em.find(EnterpriseResource.class, g.getResourceId());
            if (owner != null && owner.getTenantId() == tenant && !"STANDARD".equals(owner.getResourceKind())) type = owner.getResourceKind();
        }
        row.put("resourceType", type); row.put("resourceScope", new ResourceScope(g.getResourceScopeKind(), g.getResourceId() == null ? null : g.getResourceId().toString()));
        row.put("action", g.getAction()); row.put("effect", g.getEffect()); row.put("status", g.getStatus().name());
        var ids = "EXPLICIT".equals(g.getSchoolScopeKind()) ? schoolIds(em, tenant, g.getId(), 1, 100) : List.<String>of();
        long total = "EXPLICIT".equals(g.getSchoolScopeKind()) ? schoolTotal(em, tenant, g.getId()) : 0;
        row.put("schoolScope", Map.of("kind", g.getSchoolScopeKind(), "ids", ids, "total", total, "complete", ids.size() == total)); return row;
    }
    private static List<String> schoolIds(EntityManager em, long tenant, long grant, int page, int size) {
        return em.createQuery("select s.schoolId from EnterpriseGrantSchool s where s.tenantId=:tenant and s.grantId=:grant order by s.schoolId", Long.class)
                .setParameter("tenant", tenant).setParameter("grant", grant).setFirstResult((page - 1) * size).setMaxResults(size).getResultList().stream().map(Object::toString).toList();
    }
    private static long schoolTotal(EntityManager em, long tenant, long grant) {
        return em.createQuery("select count(s) from EnterpriseGrantSchool s where s.tenantId=:tenant and s.grantId=:grant", Long.class)
                .setParameter("tenant", tenant).setParameter("grant", grant).getSingleResult();
    }
    private static void epoch(EnterpriseTenant tenant, String expected, int page) {
        if (page > 1 && expected == null) throw ManagementFields.invalid();
        if (expected != null && tenant.getAccessEpoch() != ManagementFields.id(expected)) throw PermissionReferences.conflict();
    }
    private static <T> PageResult<T> page(List<T> rows, long total, int page, int size) { return new PageResult<>(rows, total, PageRequest.of(page - 1, size)); }
}
