package io.dataease.enterprise.permission.manage;

import io.dataease.api.permissions.enterprise.PermissionContract.*;
import io.dataease.enterprise.management.manage.ManagementAuthority;
import io.dataease.enterprise.management.server.ManagementFields;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Syntax and bounded command normalization; ownership is checked in the owning transaction. */
public final class PermissionCommands {
    private PermissionCommands() { }
    static final Set<String> TYPES = Set.of("DATASET", "DASHBOARD", "TEMPLATE", "SCHOOL_COPY");
    static final Set<String> ACTIONS = Set.of("VIEW", "EXPORT", "DRILL", "EDIT");
    static void subject(Subject value) {
        if (value == null) throw ManagementFields.invalid();
        one(value.type(), Set.of("ORG", "ROLE", "USER")); ManagementFields.id(value.id());
    }
    static void one(String value, Set<String> allowed) { if (value == null || !allowed.contains(value)) throw ManagementFields.invalid(); }
    static String state(String value) { one(value, Set.of("ACTIVE", "DISABLED")); return value; }
    static void version(String grant, String version) {
        if ((grant == null) != (version == null)) throw ManagementFields.invalid();
        if (grant != null) { ManagementFields.id(grant); ManagementFields.id(version); }
    }
    static void header(Subject subject, String epoch, String key, List<?> changes) {
        subject(subject); ManagementFields.id(epoch);
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}") || changes == null
                || changes.isEmpty() || changes.size() > 200 || changes.stream().anyMatch(java.util.Objects::isNull)) throw ManagementFields.invalid();
    }
    public static Batch normalize(Batch input) {
        if (input == null) throw ManagementFields.invalid();
        header(input.subject(), input.expectedEpoch(), input.idempotencyKey(), input.changes());
        Set<String> touched = new HashSet<>(), added = new HashSet<>();
        int schoolCount = 0;
        var result = new java.util.ArrayList<Change>();
        for (var c : input.changes()) {
            one(c.operation(), Set.of("UPSERT", "DELETE")); version(c.grantId(), c.expectedVersion());
            if (c.grantId() != null && !touched.add(c.grantId())) throw ManagementFields.invalid();
            if ("DELETE".equals(c.operation())) {
                if (c.grantId() == null || c.policyKind() != null || c.resourceType() != null || c.resourceScope() != null
                        || c.schoolScope() != null || c.action() != null || c.effect() != null || c.status() != null) throw ManagementFields.invalid();
                result.add(c); continue;
            }
            one(c.policyKind(), Set.of("DATA_ACCESS", "RESOURCE_ACTION")); one(c.resourceType(), TYPES);
            one(c.action(), ACTIONS); one(c.effect(), Set.of("ALLOW", "DENY"));
            String status = c.status() == null ? "ACTIVE" : state(c.status());
            if (c.resourceScope() == null || c.schoolScope() == null) throw ManagementFields.invalid();
            var rs = c.resourceScope(); var ss = c.schoolScope();
            one(rs.kind(), Set.of("EXACT", "ALL_DATASETS_IN_TENANT"));
            if ("EXACT".equals(rs.kind())) ManagementFields.id(rs.id());
            else if (rs.id() != null) throw ManagementFields.invalid();
            one(ss.kind(), Set.of("EXPLICIT", "ALL_ACTIVE_IN_TENANT", "ASSIGNMENT", "NONE"));
            List<String> schools = null;
            if ("EXPLICIT".equals(ss.kind())) {
                if (ss.ids() == null || ss.ids().isEmpty() || ss.ids().size() > 500
                        || new HashSet<>(ss.ids()).size() != ss.ids().size()) throw ManagementFields.invalid();
                ss.ids().forEach(ManagementFields::id);
                schools = ss.ids().stream().sorted(java.util.Comparator.comparingLong(ManagementFields::id)).toList();
                schoolCount += schools.size(); if (schoolCount > 5000) throw ManagementFields.invalid();
            } else if (ss.ids() != null) throw ManagementFields.invalid();
            if ("ASSIGNMENT".equals(ss.kind()) && !"ROLE".equals(input.subject().type())) throw ManagementFields.invalid();
            if ("DATA_ACCESS".equals(c.policyKind())) {
                if (!"DATASET".equals(c.resourceType()) || "EDIT".equals(c.action()) || "NONE".equals(ss.kind())) throw ManagementFields.invalid();
            } else {
                if ("DATASET".equals(c.resourceType()) || !"EXACT".equals(rs.kind())) throw ManagementFields.invalid();
                if ("SCHOOL_COPY".equals(c.resourceType())) {
                    if (!("ASSIGNMENT".equals(ss.kind()) || "EXPLICIT".equals(ss.kind()) && schools.size() == 1)) throw ManagementFields.invalid();
                } else if (!"NONE".equals(ss.kind())) throw ManagementFields.invalid();
            }
            var normalized = new Change(c.operation(), c.grantId(), c.expectedVersion(), c.policyKind(), c.resourceType(),
                    rs, new SchoolScope(ss.kind(), schools), c.action(), c.effect(), status);
            if (c.grantId() == null && !added.add(key(normalized))) throw ManagementFields.invalid();
            result.add(normalized);
        }
        return new Batch(input.subject(), input.expectedEpoch(), input.idempotencyKey(), List.copyOf(result));
    }
    public static CapabilityBatch normalize(CapabilityBatch input) {
        if (input == null) throw ManagementFields.invalid();
        header(input.subject(), input.expectedEpoch(), input.idempotencyKey(), input.changes());
        Set<String> touched = new HashSet<>(), added = new HashSet<>();
        for (var c : input.changes()) {
            one(c.operation(), Set.of("UPSERT", "DELETE")); version(c.grantId(), c.expectedVersion());
            if (c.grantId() != null && !touched.add(c.grantId())) throw ManagementFields.invalid();
            if ("DELETE".equals(c.operation())) {
                if (c.grantId() == null || c.capability() != null || c.effect() != null || c.status() != null) throw ManagementFields.invalid();
            } else {
                one(c.capability(), ManagementAuthority.CAPABILITIES); one(c.effect(), Set.of("ALLOW", "DENY")); state(c.status());
                if (c.grantId() == null && !added.add(c.capability() + "|" + c.effect())) throw ManagementFields.invalid();
            }
        }
        return new CapabilityBatch(input.subject(), input.expectedEpoch(), input.idempotencyKey(), List.copyOf(input.changes()));
    }
    static String key(Change c) {
        return String.join("|", c.policyKind(), storageType(c.resourceType()), c.resourceScope().kind(),
                c.resourceScope().id() == null ? "0" : c.resourceScope().id(), c.action(), c.effect(), c.schoolScope().kind());
    }
    static String storageType(String type) { return "DATASET".equals(type) ? type : "DASHBOARD"; }
    static String resourceKind(String type) { return Set.of("TEMPLATE", "SCHOOL_COPY").contains(type) ? type : "STANDARD"; }
}
