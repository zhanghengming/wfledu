package io.dataease.enterprise.permission.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.permissions.enterprise.PermissionContract.*;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.ManagementEvent;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.enterprise.identity.persistence.*;
import io.dataease.enterprise.management.manage.*;
import io.dataease.enterprise.management.persistence.*;
import io.dataease.enterprise.management.server.ManagementFields;
import io.dataease.enterprise.permission.persistence.*;
import io.dataease.result.ResultCode;
import io.dataease.utils.IDUtils;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiFunction;

/** Owning bounded transaction: current authority, complete command, one epoch/audit, then durable replay result. */
public final class PermissionBatchService {
    private final ManagementTransactions transactions;
    private final GroupAdministrationInvariant invariant;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    public PermissionBatchService(ManagementTransactions transactions, GroupAdministrationInvariant invariant, Clock clock) {
        this.transactions = transactions; this.invariant = invariant; this.clock = clock;
    }
    public BatchResult permissions(Principal principal, Batch input) {
        Batch command = PermissionCommands.normalize(input);
        return execute(principal, command.subject(), command.expectedEpoch(), command.idempotencyKey(), "PERMISSIONS_BATCH", command,
                ManagementEvent.PERMISSIONS_BATCH, command.changes().stream().anyMatch(c -> "UPSERT".equals(c.operation())),
                (em, scope) -> permissions(em, scope, command.changes()));
    }
    public BatchResult capabilities(Principal principal, CapabilityBatch input) {
        CapabilityBatch command = PermissionCommands.normalize(input);
        return execute(principal, command.subject(), command.expectedEpoch(), command.idempotencyKey(), "ADMIN_CAPABILITIES_BATCH", command,
                ManagementEvent.ADMIN_CAPABILITIES_BATCH, command.changes().stream().anyMatch(c -> "UPSERT".equals(c.operation())),
                (em, scope) -> capabilities(em, scope, command.changes()));
    }
    private record Scope(long tenant, long subject, long actor, LocalDateTime now) { }
    private BatchResult execute(Principal principal, Subject subject, String expectedEpoch, String key, String operation, Object command,
                                ManagementEvent event, boolean requireActiveTarget, BiFunction<EntityManager, Scope, List<ChangeResult>> mutate) {
        byte[] digest = digest(operation, command);
        return transactions.group(principal, "MANAGE_AUTHORIZATION", true, (em, tenant) -> {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            var previous = em.createQuery("from EnterpriseIdempotency where tenantId=:tenant and principalKind='USER' and userId=:user and operation=:operation and idempotencyKey=:key", EnterpriseIdempotency.class)
                    .setParameter("tenant", tenant.getId()).setParameter("user", principal.userId()).setParameter("operation", operation).setParameter("key", key).getResultList();
            if (!previous.isEmpty()) {
                var row = previous.getFirst();
                if (previous.size() != 1 || !MessageDigest.isEqual(digest, row.getRequestDigest()) || !"DONE".equals(row.getState())
                        || !row.getExpiresAt().isAfter(now) || row.getResultMetadata() == null) throw PermissionReferences.conflict();
                StoredResult result = decode(row.getResultMetadata());
                return new BatchResult(result.results(), result.committedEpoch(), tenant.getAccessEpoch().toString(), true);
            }
            PermissionReferences.version(tenant.getAccessEpoch(), expectedEpoch);
            if (tenant.getVersion() == Long.MAX_VALUE) throw PermissionReferences.conflict();
            var target = PermissionReferences.subject(em, tenant.getId(), subject, requireActiveTarget);
            // Existing disabled subjects remain revocable; a DELETE-only command never manufactures a subject.
            if (!requireActiveTarget && target.subjectId() == null) throw PermissionReferences.missing();
            long subjectId = PermissionReferences.ensureSubject(em, tenant.getId(), target, principal.userId(), now);
            List<ChangeResult> results = mutate.apply(em, new Scope(tenant.getId(), subjectId, principal.userId(), now));
            long epoch = tenant.getAccessEpoch() + 1;
            tenant.setAccessEpoch(epoch); tenant.setUpdatedAt(now); tenant.setUpdatedBy(principal.userId());
            long tenantId = tenant.getId();
            // Flush/clear inside the invariant also checks the new capability state, never the old managed state.
            invariant.require(em, tenantId);
            em.persist(EnterpriseAuditEvent.management(IDUtils.snowID(), now, tenantId, principal.userId(), event, subjectId, epoch, 1));
            var stored = PermissionReferences.initialize(new EnterpriseIdempotency(), principal.userId(), now);
            stored.setTenantId(tenantId); stored.setPrincipalKind("USER"); stored.setUserId(principal.userId());
            stored.setOperation(operation); stored.setIdempotencyKey(key); stored.setRequestDigest(digest); stored.setState("DONE");
            stored.setExpiresAt(now.plusHours(24)); stored.setResultMetadata(encode(new StoredResult(results, Long.toString(epoch))));
            em.persist(stored); em.flush();
            return new BatchResult(results, Long.toString(epoch), Long.toString(epoch), false);
        });
    }
    private List<ChangeResult> permissions(EntityManager em, Scope scope, List<Change> commands) {
        Map<Long, EnterpriseGrant> previous = new HashMap<>();
        Set<Long> remove = new HashSet<>(), schools = new HashSet<>();
        for (var c : commands) {
            if (c.grantId() != null) {
                long id = ManagementFields.id(c.grantId());
                var old = em.find(EnterpriseGrant.class, id);
                if (old == null || old.getTenantId() != scope.tenant() || old.getSubjectId() != scope.subject()) throw PermissionReferences.missing();
                PermissionReferences.version(old.getVersion(), c.expectedVersion()); previous.put(id, old);
                if ("DELETE".equals(c.operation())) { remove.add(id); continue; }
                if (!key(old).equals(PermissionCommands.key(c))) throw ManagementFields.invalid();
            }
            if ("EXPLICIT".equals(c.schoolScope().kind())) c.schoolScope().ids().forEach(v -> schools.add(ManagementFields.id(v)));
            if ("EXACT".equals(c.resourceScope().kind())) {
                var resource = PermissionReferences.resource(em, scope.tenant(), c.resourceType(), ManagementFields.id(c.resourceScope().id()));
                if ("SCHOOL_COPY".equals(c.resourceType()) && "EXPLICIT".equals(c.schoolScope().kind())
                        && !resource.getSchoolId().equals(ManagementFields.id(c.schoolScope().ids().getFirst()))) throw PermissionReferences.missing();
            }
        }
        PermissionReferences.organizations(em, scope.tenant(), schools, true);
        // Validate new natural keys before the first mutation. Deleted rows may be replaced atomically.
        for (var c : commands) if ("UPSERT".equals(c.operation()) && c.grantId() == null) {
            var collision = em.createQuery("select g.id from EnterpriseGrant g where g.tenantId=:tenant and g.subjectId=:subject and g.policyKind=:policy "
                            + "and g.resourceType=:type and g.resourceScopeKind=:resourceScope and g.resourceKey=:resourceKey and g.action=:action and g.effect=:effect and g.schoolScopeKind=:schoolScope", Long.class)
                    .setParameter("tenant", scope.tenant()).setParameter("subject", scope.subject()).setParameter("policy", c.policyKind())
                    .setParameter("type", PermissionCommands.storageType(c.resourceType())).setParameter("resourceScope", c.resourceScope().kind())
                    .setParameter("resourceKey", c.resourceScope().id() == null ? 0L : ManagementFields.id(c.resourceScope().id()))
                    .setParameter("action", c.action()).setParameter("effect", c.effect()).setParameter("schoolScope", c.schoolScope().kind()).getResultList();
            if (collision.stream().anyMatch(id -> !remove.contains(id))) throw PermissionReferences.error(ResultCode.DATA_ALREADY_EXISTED);
        }
        for (Long id : remove) {
            deleteSchools(em, scope.tenant(), id);
            if (em.createQuery("delete from EnterpriseGrant where tenantId=:tenant and subjectId=:subject and id=:id and version=:version")
                    .setParameter("tenant", scope.tenant()).setParameter("subject", scope.subject()).setParameter("id", id)
                    .setParameter("version", previous.get(id).getVersion()).executeUpdate() != 1) throw PermissionReferences.conflict();
        }
        em.flush();
        var results = new ArrayList<ChangeResult>();
        for (var c : commands) {
            if ("DELETE".equals(c.operation())) { results.add(new ChangeResult("DELETE", c.grantId(), c.expectedVersion())); continue; }
            long id, version;
            if (c.grantId() == null) {
                var row = PermissionReferences.initialize(new EnterpriseGrant(), scope.actor(), scope.now());
                row.setTenantId(scope.tenant()); row.setSubjectId(scope.subject()); row.setPolicyKind(c.policyKind());
                row.setResourceType(PermissionCommands.storageType(c.resourceType())); row.setResourceScopeKind(c.resourceScope().kind());
                row.setResourceId(ManagementFields.optionalId(c.resourceScope().id())); row.setAction(c.action()); row.setEffect(c.effect());
                row.setSchoolScopeKind(c.schoolScope().kind()); row.setStatus(FoundationStatus.valueOf(c.status()));
                em.persist(row); em.flush(); id = row.getId(); version = 1;
            } else {
                id = ManagementFields.id(c.grantId()); version = ManagementFields.id(c.expectedVersion()) + 1;
                if (em.createQuery("update EnterpriseGrant set status=:status,version=version+1,updatedAt=:time,updatedBy=:actor where tenantId=:tenant and subjectId=:subject and id=:id and version=:version")
                        .setParameter("status", FoundationStatus.valueOf(c.status())).setParameter("time", scope.now()).setParameter("actor", scope.actor())
                        .setParameter("tenant", scope.tenant()).setParameter("subject", scope.subject()).setParameter("id", id)
                        .setParameter("version", ManagementFields.id(c.expectedVersion())).executeUpdate() != 1) throw PermissionReferences.conflict();
                deleteSchools(em, scope.tenant(), id);
            }
            if ("EXPLICIT".equals(c.schoolScope().kind())) for (String school : c.schoolScope().ids()) {
                var child = PermissionReferences.initialize(new EnterpriseGrantSchool(), scope.actor(), scope.now());
                child.setTenantId(scope.tenant()); child.setGrantId(id); child.setSchoolId(ManagementFields.id(school)); em.persist(child);
            }
            results.add(new ChangeResult("UPSERT", Long.toString(id), Long.toString(version)));
        }
        return List.copyOf(results);
    }
    private List<ChangeResult> capabilities(EntityManager em, Scope scope, List<CapabilityChange> commands) {
        Map<Long, EnterpriseAdminGrant> previous = new HashMap<>(); Set<Long> remove = new HashSet<>();
        for (var c : commands) if (c.grantId() != null) {
            long id = ManagementFields.id(c.grantId()); var old = em.find(EnterpriseAdminGrant.class, id);
            if (old == null || old.getTenantId() != scope.tenant() || old.getSubjectId() != scope.subject()) throw PermissionReferences.missing();
            PermissionReferences.version(old.getVersion(), c.expectedVersion()); previous.put(id, old);
            if ("DELETE".equals(c.operation())) remove.add(id);
            else if (!old.getCapability().equals(c.capability()) || !old.getEffect().equals(c.effect())) throw ManagementFields.invalid();
        }
        for (var c : commands) if ("UPSERT".equals(c.operation()) && c.grantId() == null) {
            var collision = em.createQuery("select g.id from EnterpriseAdminGrant g where g.tenantId=:tenant and g.subjectId=:subject and g.capability=:capability and g.effect=:effect", Long.class)
                    .setParameter("tenant", scope.tenant()).setParameter("subject", scope.subject()).setParameter("capability", c.capability()).setParameter("effect", c.effect()).getResultList();
            if (collision.stream().anyMatch(id -> !remove.contains(id))) throw PermissionReferences.error(ResultCode.DATA_ALREADY_EXISTED);
        }
        for (Long id : remove) if (em.createQuery("delete from EnterpriseAdminGrant where tenantId=:tenant and subjectId=:subject and id=:id and version=:version")
                .setParameter("tenant", scope.tenant()).setParameter("subject", scope.subject()).setParameter("id", id)
                .setParameter("version", previous.get(id).getVersion()).executeUpdate() != 1) throw PermissionReferences.conflict();
        em.flush(); var results = new ArrayList<ChangeResult>();
        for (var c : commands) {
            if ("DELETE".equals(c.operation())) { results.add(new ChangeResult("DELETE", c.grantId(), c.expectedVersion())); continue; }
            long id, version;
            if (c.grantId() == null) {
                var row = PermissionReferences.initialize(new EnterpriseAdminGrant(), scope.actor(), scope.now());
                row.setTenantId(scope.tenant()); row.setSubjectId(scope.subject()); row.setCapability(c.capability()); row.setEffect(c.effect());
                row.setStatus(FoundationStatus.valueOf(c.status())); em.persist(row); id = row.getId(); version = 1;
            } else {
                id = ManagementFields.id(c.grantId()); version = ManagementFields.id(c.expectedVersion()) + 1;
                if (em.createQuery("update EnterpriseAdminGrant set status=:status,version=version+1,updatedAt=:time,updatedBy=:actor where tenantId=:tenant and subjectId=:subject and id=:id and version=:version")
                        .setParameter("status", FoundationStatus.valueOf(c.status())).setParameter("time", scope.now()).setParameter("actor", scope.actor())
                        .setParameter("tenant", scope.tenant()).setParameter("subject", scope.subject()).setParameter("id", id)
                        .setParameter("version", ManagementFields.id(c.expectedVersion())).executeUpdate() != 1) throw PermissionReferences.conflict();
            }
            results.add(new ChangeResult("UPSERT", Long.toString(id), Long.toString(version)));
        }
        return List.copyOf(results);
    }
    private static void deleteSchools(EntityManager em, long tenant, long id) {
        em.createQuery("delete from EnterpriseGrantSchool where tenantId=:tenant and grantId=:id").setParameter("tenant", tenant).setParameter("id", id).executeUpdate();
    }
    private static String key(EnterpriseGrant g) {
        return String.join("|", g.getPolicyKind(), g.getResourceType(), g.getResourceScopeKind(), g.getResourceId() == null ? "0" : g.getResourceId().toString(), g.getAction(), g.getEffect(), g.getSchoolScopeKind());
    }
    private byte[] digest(String operation, Object command) {
        try { return MessageDigest.getInstance("SHA-256").digest((operation + "\n" + encode(command)).getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String encode(Object value) {
        try {
            String text = json.writeValueAsString(value);
            if (text.getBytes(StandardCharsets.UTF_8).length > 65536) throw ManagementFields.invalid(); return text;
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Permission result serialization failed", failure); }
    }
    private StoredResult decode(String text) {
        try {
            if (text.getBytes(StandardCharsets.UTF_8).length > 65536) throw PermissionReferences.conflict();
            var result = json.readValue(text, StoredResult.class);
            if (result == null || result.results() == null || result.results().isEmpty() || result.results().size() > 200
                    || result.results().stream().anyMatch(Objects::isNull)) throw PermissionReferences.conflict();
            ManagementFields.id(result.committedEpoch());
            for (var item : result.results()) {
                PermissionCommands.one(item.operation(), Set.of("UPSERT", "DELETE")); ManagementFields.id(item.grantId()); ManagementFields.id(item.version());
            }
            return result;
        } catch (JsonProcessingException failure) { throw PermissionReferences.conflict(); }
    }
}
