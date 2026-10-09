package io.dataease.enterprise.permission.manage;

import io.dataease.api.permissions.enterprise.PermissionContract.Subject;
import io.dataease.dao.auto.entity.DataVisualizationInfo;
import io.dataease.dao.auto.entity.CoreDatasetGroup;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.persistence.*;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.enterprise.tenant.domain.OrganizationHierarchy;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;

/** Tenant-scoped references; configuration reads never manufacture missing subjects. */
final class PermissionReferences {
    record Target(String type, long id, Long subjectId) { }
    static Target subject(EntityManager em, long tenant, Subject input, boolean active) {
        PermissionCommands.subject(input);
        long id = ManagementFields.id(input.id());
        String field;
        Long reference = id;
        if ("ORG".equals(input.type())) {
            var org = em.find(EnterpriseOrganization.class, id);
            if (org == null || org.getTenantId() != tenant) throw missing();
            if (active) organizations(em, tenant, Set.of(id), false);
            field = "orgId";
        } else if ("ROLE".equals(input.type())) {
            var role = em.find(EnterpriseRole.class, id);
            if (role == null || role.getTenantId() != tenant || active && role.getStatus() != FoundationStatus.ACTIVE) throw missing();
            field = "roleId";
        } else {
            var members = em.createQuery("from EnterpriseTenantMember where tenantId=:tenant and userId=:user", EnterpriseTenantMember.class)
                    .setParameter("tenant", tenant).setParameter("user", id).getResultList();
            var user = em.find(EnterpriseUser.class, id);
            if (members.size() != 1 || user == null || active && (members.getFirst().getStatus() != FoundationStatus.ACTIVE
                    || user.getStatus() != FoundationStatus.ACTIVE)) throw missing();
            reference = members.getFirst().getId(); field = "memberId";
        }
        var rows = em.createQuery("from EnterpriseSubject where tenantId=:tenant and subjectType=:type and " + field + "=:ref", EnterpriseSubject.class)
                .setParameter("tenant", tenant).setParameter("type", input.type()).setParameter("ref", reference).getResultList();
        if (rows.size() > 1 || rows.isEmpty() && !"ORG".equals(input.type())) throw missing();
        return new Target(input.type(), id, rows.isEmpty() ? null : rows.getFirst().getId());
    }
    static long ensureSubject(EntityManager em, long tenant, Target target, long actor, LocalDateTime now) {
        if (target.subjectId() != null) return target.subjectId();
        if (!"ORG".equals(target.type())) throw missing();
        var row = initialize(new EnterpriseSubject(), actor, now);
        row.setTenantId(tenant); row.setSubjectType("ORG"); row.setOrgId(target.id()); em.persist(row); em.flush();
        return row.getId();
    }
    static Map<Long, EnterpriseOrganization> organizations(EntityManager em, long tenant, Set<Long> requested, boolean schools) {
        Map<Long, EnterpriseOrganization> nodes = new HashMap<>();
        Set<Long> pending = new HashSet<>(requested);
        for (int depth = 0; !pending.isEmpty(); depth++) {
            if (depth >= 128) throw missing();
            var ids = new ArrayList<>(pending); pending.clear();
            for (int offset = 0; offset < ids.size(); offset += 500) {
                var batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
                var found = em.createQuery("from EnterpriseOrganization where tenantId=:tenant and id in :ids", EnterpriseOrganization.class)
                        .setParameter("tenant", tenant).setParameter("ids", batch).getResultList();
                if (found.size() != batch.size()) throw missing();
                for (var node : found) {
                    if (node.getStatus() != FoundationStatus.ACTIVE) throw missing();
                    nodes.put(node.getId(), node);
                    if (node.getParentId() != null && !nodes.containsKey(node.getParentId())) pending.add(node.getParentId());
                    if (node.getSchoolId() != null && !nodes.containsKey(node.getSchoolId())) pending.add(node.getSchoolId());
                }
            }
            pending.removeAll(nodes.keySet());
        }
        for (Long id : requested) {
            var node = nodes.get(id);
            if (node == null || schools && node.getKind() != EnterpriseOrganization.Kind.SCHOOL) throw missing();
            try {
                new OrganizationHierarchy(128).requireAvailable(node(node), other -> {
                    var related = nodes.get(other); return related == null ? null : node(related);
                });
            } catch (DEException invalidReference) { throw missing(); }
        }
        return nodes;
    }
    static EnterpriseResource resource(EntityManager em, long tenant, String type, long id) {
        var owner = em.find(EnterpriseResource.class, id);
        if (owner == null || owner.getTenantId() != tenant || owner.getStatus() != FoundationStatus.ACTIVE
                || !PermissionCommands.storageType(type).equals(owner.getResourceType())
                || !PermissionCommands.resourceKind(type).equals(owner.getResourceKind())) throw missing();
        if ("DATASET".equals(type)) {
            var nativeRow = em.find(CoreDatasetGroup.class, id);
            if (nativeRow == null || !"dataset".equals(nativeRow.getNodeType())) throw missing();
        } else {
            var nativeRow = em.find(DataVisualizationInfo.class, id);
            if (nativeRow == null || !"panel".equals(nativeRow.getNodeType()) || !"dashboard".equals(nativeRow.getType())
                    || Boolean.TRUE.equals(nativeRow.getDeleteFlag()) || !Objects.equals(nativeRow.getOrgId(), tenant)) throw missing();
        }
        if ("SCHOOL_COPY".equals(type)) {
            if (owner.getSchoolId() == null || owner.getParentResourceId() == null) throw missing();
            organizations(em, tenant, Set.of(owner.getSchoolId()), true);
            var parent = em.find(EnterpriseResource.class, owner.getParentResourceId());
            if (parent == null || parent.getTenantId() != tenant || !"TEMPLATE".equals(parent.getResourceKind())
                    || !"DASHBOARD".equals(parent.getResourceType()) || parent.getStatus() != FoundationStatus.ACTIVE) throw missing();
        }
        return owner;
    }
    static <T extends FoundationRecord> T initialize(T row, long actor, LocalDateTime now) {
        row.setId(IDUtils.snowID()); row.setVersion(1L); row.setCreatedAt(now); row.setUpdatedAt(now);
        row.setCreatedBy(actor); row.setUpdatedBy(actor); return row;
    }
    private static OrganizationNode node(EnterpriseOrganization row) {
        return new OrganizationNode(row.getId(), row.getTenantId(), row.getParentId(),
                OrganizationNode.Kind.valueOf(row.getKind().name()), row.getName(), row.getSchoolCode(), row.getSchoolId(),
                OrganizationNode.Status.valueOf(row.getStatus().name()), row.getVersion());
    }
    static void version(long actual, String expected) {
        if (actual == Long.MAX_VALUE || actual != ManagementFields.id(expected)) throw conflict();
    }
    static DEException missing() { return error(ResultCode.RESOURCE_NOT_EXIST); }
    static DEException conflict() { return error(ResultCode.DATA_IS_WRONG); }
    static DEException error(ResultCode code) { return new DEException(code.code(), code.message()); }
}
