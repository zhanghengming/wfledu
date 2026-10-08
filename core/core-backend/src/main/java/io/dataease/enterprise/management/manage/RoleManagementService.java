package io.dataease.enterprise.management.manage;

import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.persistence.*;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.ManagementEvent;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.enterprise.tenant.domain.OrganizationHierarchy;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.exception.DEException;
import io.dataease.result.PageResult;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class RoleManagementService {
    public record RoleSave(boolean create, Long id, Long expectedVersion, String code, String name, FoundationStatus status) { }
    public record AssignmentSave(boolean create, Long id, Long expectedVersion, long memberId, long roleId,
                                 List<Long> schoolIds, FoundationStatus status) { }
    private final ManagementTransactions transactions;
    private final GroupAdministrationInvariant invariant;
    private final ManagementPrivilegeGuard privileges;
    private final Clock clock;
    public RoleManagementService(ManagementTransactions transactions, GroupAdministrationInvariant invariant,
                                 ManagementPrivilegeGuard privileges, Clock clock) {
        this.transactions = transactions; this.invariant = invariant; this.privileges = privileges; this.clock = clock;
    }

    public PageResult<Map<String,Object>> roles(Principal principal, int page, int size) {
        return transactions.group(principal, "MANAGE_ROLES", false, (em, tenant) -> {
            var records = em.createQuery("from EnterpriseRole where tenantId=:tenant order by id", EnterpriseRole.class)
                    .setParameter("tenant", tenant.getId()).setFirstResult((page - 1) * size).setMaxResults(size)
                    .getResultList().stream().map(r -> Map.<String,Object>of("id", r.getId().toString(),
                        "version", r.getVersion().toString(), "code", r.getCode(), "name", r.getName(), "status", r.getStatus().name())).toList();
            long total = em.createQuery("select count(r) from EnterpriseRole r where r.tenantId=:tenant", Long.class)
                    .setParameter("tenant", tenant.getId()).getSingleResult();
            return new PageResult<>(records, total, PageRequest.of(page - 1, size));
        });
    }

    public Map<String,String> saveRole(Principal principal, RoleSave command) {
        if (command == null || command.status() == null || command.code() == null
                || !command.code().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw ManagementFields.invalid();
        ManagementFields.text(command.name(), 128);
        branch(command.create(), command.id(), command.expectedVersion());
        return transactions.group(principal, "MANAGE_ROLES", true, (em, tenant) -> {
            long id, version;
            var now = now();
            ManagementPrivilegeGuard.Snapshot snapshot;
            if (command.create()) {
                if (em.createQuery("select count(r) from EnterpriseRole r where r.tenantId=:tenant and r.code=:code", Long.class)
                        .setParameter("tenant", tenant.getId()).setParameter("code", command.code()).getSingleResult() != 0) throw error(ResultCode.DATA_ALREADY_EXISTED);
                snapshot = privileges.capture(em, tenant.getId(), List.of());
                id = IDUtils.snowID(); version = 1;
                var role = PlatformManagementService.record(new EnterpriseRole(), id, principal.userId(), now);
                role.setTenantId(tenant.getId()); role.setCode(command.code()); role.setName(command.name()); role.setStatus(command.status()); em.persist(role); em.flush();
                var subject = PlatformManagementService.record(new EnterpriseSubject(), IDUtils.snowID(), principal.userId(), now);
                subject.setTenantId(tenant.getId()); subject.setSubjectType("ROLE"); subject.setRoleId(id); em.persist(subject);
            } else {
                var role = role(em, tenant.getId(), command.id());
                if (!role.getCode().equals(command.code())) throw ManagementFields.invalid();
                version(role.getVersion(), command.expectedVersion());
                snapshot = privileges.capture(em, tenant.getId(), role.getStatus() == command.status()
                        ? List.of() : privileges.roleUsers(em, tenant.getId(), role.getId()));
                id = role.getId(); version = role.getVersion() + 1;
                if (em.createQuery("update EnterpriseRole set name=:name,status=:status,version=version+1,updatedAt=:time,updatedBy=:actor "
                                + "where tenantId=:tenant and id=:id and version=:version")
                        .setParameter("name", command.name()).setParameter("status", command.status()).setParameter("time", now)
                        .setParameter("actor", principal.userId()).setParameter("tenant", tenant.getId()).setParameter("id", id)
                        .setParameter("version", command.expectedVersion()).executeUpdate() != 1) throw error(ResultCode.DATA_IS_WRONG);
            }
            return finish(em, tenant, principal, id, version, now, snapshot,
                    command.create() ? ManagementEvent.ROLE_CREATED : ManagementEvent.ROLE_UPDATED);
        });
    }

    public Map<String,String> saveAssignment(Principal principal, AssignmentSave command) {
        if (command == null || command.status() == null || command.memberId() <= 0 || command.roleId() <= 0
                || command.schoolIds() == null || command.schoolIds().size() > 500
                || command.schoolIds().stream().anyMatch(v -> v == null || v <= 0)
                || new HashSet<>(command.schoolIds()).size() != command.schoolIds().size()
                || command.status() == FoundationStatus.ACTIVE && command.schoolIds().isEmpty()) throw ManagementFields.invalid();
        branch(command.create(), command.id(), command.expectedVersion());
        return transactions.group(principal, "MANAGE_ROLES", true, (em, tenant) -> {
            var member = member(em, tenant.getId(), command.memberId());
            var role = role(em, tenant.getId(), command.roleId());
            if (command.status() == FoundationStatus.ACTIVE) {
                var user = em.find(EnterpriseUser.class, member.getUserId());
                if (member.getStatus() != FoundationStatus.ACTIVE || role.getStatus() != FoundationStatus.ACTIVE
                        || user == null || user.getStatus() != FoundationStatus.ACTIVE) throw error(ResultCode.PERMISSION_NO_ACCESS);
            }
            for (Long school : command.schoolIds()) {
                var node = MemberManagementService.node(em, tenant.getId(), school);
                if (node.kind() != OrganizationNode.Kind.SCHOOL) throw ManagementFields.invalid();
                new OrganizationHierarchy(128).requireAvailable(node, id -> MemberManagementService.findNode(em, tenant.getId(), id));
            }
            var snapshot = privileges.capture(em, tenant.getId(), List.of(member.getUserId()));
            long id, version; var now = now();
            if (command.create()) {
                if (em.createQuery("select count(a) from EnterpriseRoleAssignment a where a.tenantId=:tenant and a.memberId=:member and a.roleId=:role", Long.class)
                        .setParameter("tenant", tenant.getId()).setParameter("member", command.memberId()).setParameter("role", command.roleId()).getSingleResult() != 0) throw error(ResultCode.DATA_ALREADY_EXISTED);
                id = IDUtils.snowID(); version = 1;
                var assignment = PlatformManagementService.record(new EnterpriseRoleAssignment(), id, principal.userId(), now);
                assignment.setTenantId(tenant.getId()); assignment.setMemberId(command.memberId()); assignment.setRoleId(command.roleId());
                assignment.setStatus(command.status()); em.persist(assignment); em.flush();
            } else {
                var previous = em.createQuery("from EnterpriseRoleAssignment where tenantId=:tenant and id=:id", EnterpriseRoleAssignment.class)
                        .setParameter("tenant", tenant.getId()).setParameter("id", command.id()).getResultList();
                if (previous.size() != 1) throw error(ResultCode.RESOURCE_NOT_EXIST);
                var assignment = previous.getFirst();
                if (assignment.getMemberId() != command.memberId() || assignment.getRoleId() != command.roleId()) throw ManagementFields.invalid();
                version(assignment.getVersion(), command.expectedVersion()); id = assignment.getId(); version = assignment.getVersion() + 1;
                if (em.createQuery("update EnterpriseRoleAssignment set status=:status,version=version+1,updatedAt=:time,updatedBy=:actor "
                                + "where tenantId=:tenant and id=:id and version=:version")
                        .setParameter("status", command.status()).setParameter("time", now).setParameter("actor", principal.userId())
                        .setParameter("tenant", tenant.getId()).setParameter("id", id).setParameter("version", command.expectedVersion()).executeUpdate() != 1) throw error(ResultCode.DATA_IS_WRONG);
                em.createQuery("delete from EnterpriseAssignmentSchool where tenantId=:tenant and assignmentId=:id")
                        .setParameter("tenant", tenant.getId()).setParameter("id", id).executeUpdate();
            }
            for (Long school : command.schoolIds()) {
                var item = PlatformManagementService.record(new EnterpriseAssignmentSchool(), IDUtils.snowID(), principal.userId(), now);
                item.setTenantId(tenant.getId()); item.setAssignmentId(id); item.setSchoolId(school); em.persist(item);
            }
            return finish(em, tenant, principal, id, version, now, snapshot,
                    command.create() ? ManagementEvent.ASSIGNMENT_CREATED : ManagementEvent.ASSIGNMENT_UPDATED);
        });
    }

    public PageResult<Map<String,Object>> assignments(Principal principal, Long member, Long role, Long school, int page, int size) {
        return transactions.group(principal, "MANAGE_ROLES", false, (em, tenant) -> {
            if (member != null) member(em, tenant.getId(), member);
            if (role != null) role(em, tenant.getId(), role);
            if (school != null && MemberManagementService.node(em, tenant.getId(), school).kind() != OrganizationNode.Kind.SCHOOL) throw ManagementFields.invalid();
            String where = " where a.tenantId=:tenant";
            if (member != null) where += " and a.memberId=:member";
            if (role != null) where += " and a.roleId=:role";
            if (school != null) where += " and a.id in (select s.assignmentId from EnterpriseAssignmentSchool s where s.tenantId=:tenant and s.schoolId=:school)";
            var query = em.createQuery("select a from EnterpriseRoleAssignment a" + where + " order by a.id", EnterpriseRoleAssignment.class);
            var count = em.createQuery("select count(a) from EnterpriseRoleAssignment a" + where, Long.class);
            for (var q : List.of(query, count)) {
                q.setParameter("tenant", tenant.getId());
                if (member != null) q.setParameter("member", member);
                if (role != null) q.setParameter("role", role);
                if (school != null) q.setParameter("school", school);
            }
            var roots = query.setFirstResult((page - 1) * size).setMaxResults(size).getResultList();
            Map<Long,List<String>> schools = new HashMap<>();
            if (!roots.isEmpty()) {
                for (var item : em.createQuery("select s.assignmentId,s.schoolId from EnterpriseAssignmentSchool s "
                                + "where s.tenantId=:tenant and s.assignmentId in :ids order by s.schoolId", Object[].class)
                        .setParameter("tenant", tenant.getId()).setParameter("ids", roots.stream().map(EnterpriseRoleAssignment::getId).toList()).getResultList()) {
                    schools.computeIfAbsent((Long) item[0], ignored -> new ArrayList<>()).add(item[1].toString());
                }
            }
            var records = roots.stream().map(a -> Map.<String,Object>of("id", a.getId().toString(), "version", a.getVersion().toString(),
                    "memberId", a.getMemberId().toString(), "roleId", a.getRoleId().toString(), "status", a.getStatus().name(),
                    "schoolIds", schools.getOrDefault(a.getId(), List.of()))).toList();
            return new PageResult<>(records, count.getSingleResult(), PageRequest.of(page - 1, size));
        });
    }

    private Map<String,String> finish(EntityManager em, EnterpriseTenant tenant, Principal principal, long id, long version,
                                     LocalDateTime time, ManagementPrivilegeGuard.Snapshot snapshot, ManagementEvent event) {
        if (tenant.getAccessEpoch() == Long.MAX_VALUE || tenant.getVersion() == Long.MAX_VALUE) throw error(ResultCode.DATA_IS_WRONG);
        long epoch = tenant.getAccessEpoch() + 1;
        tenant.setAccessEpoch(epoch); tenant.setUpdatedAt(time); tenant.setUpdatedBy(principal.userId());
        privileges.verify(em, snapshot); invariant.require(em, tenant.getId());
        em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(), time, tenant.getId(), principal.userId(), event, id, epoch, version));
        em.flush();
        return Map.of("id", Long.toString(id), "version", Long.toString(version), "accessEpoch", Long.toString(epoch));
    }
    private EnterpriseRole role(EntityManager em, long tenant, long id) {
        var rows = em.createQuery("from EnterpriseRole where tenantId=:tenant and id=:id", EnterpriseRole.class)
                .setParameter("tenant", tenant).setParameter("id", id).getResultList();
        if (rows.size() != 1) throw error(ResultCode.RESOURCE_NOT_EXIST); return rows.getFirst();
    }
    private EnterpriseTenantMember member(EntityManager em, long tenant, long id) {
        var rows = em.createQuery("from EnterpriseTenantMember where tenantId=:tenant and id=:id", EnterpriseTenantMember.class)
                .setParameter("tenant", tenant).setParameter("id", id).getResultList();
        if (rows.size() != 1) throw error(ResultCode.RESOURCE_NOT_EXIST); return rows.getFirst();
    }
    private static void branch(boolean create, Long id, Long version) {
        if (create ? id != null || version != null : id == null || id <= 0 || version == null || version <= 0) throw ManagementFields.invalid();
    }
    private static void version(long actual, long expected) { if (actual != expected || actual == Long.MAX_VALUE) throw error(ResultCode.DATA_IS_WRONG); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private static DEException error(ResultCode code) { return new DEException(code.code(), code.message()); }
}
