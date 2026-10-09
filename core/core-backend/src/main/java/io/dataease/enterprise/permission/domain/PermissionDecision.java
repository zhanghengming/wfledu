package io.dataease.enterprise.permission.domain;

import java.util.*;

/** Pure business policy evaluation. A binding retains one role's own assignment scope. */
public final class PermissionDecision {
    public record Rule(long id, long version, String action, String effect, String schoolKind, Set<Long> schools) {
        public Rule { schools = Set.copyOf(schools); }
    }
    public record Binding(String subjectType, long subjectRef, Long assignmentId, Set<Long> assignmentSchools, List<Rule> rules) {
        public Binding { assignmentSchools = Set.copyOf(assignmentSchools); rules = List.copyOf(rules); }
    }
    public record Facts(long tenantId, long userId, long identityEpoch, long accessEpoch, long resourceId,
                        long resourceVersion, String resourceType, Long resourceSchool, boolean memberActive,
                        boolean platformView, Set<Long> activeSchools, List<Binding> bindings) {
        public Facts { activeSchools = Set.copyOf(activeSchools); bindings = List.copyOf(bindings); }
    }
    public record Source(String grantId, String version, String subjectType, String subjectId, String assignmentId,
                         String action, String effect, List<String> matchedSchoolIds) {
        public Source { matchedSchoolIds = List.copyOf(matchedSchoolIds); }
    }
    public record Result(boolean authorizationAllowed, String reason, List<String> allowedSchoolIds,
                         List<Source> sources) {
        public Result { allowedSchoolIds = List.copyOf(allowedSchoolIds); sources = List.copyOf(sources); }
    }
    private record Scope(boolean resource, Set<Long> schools, List<Source> sources, boolean complete) { }

    public Result evaluate(Facts facts, String action) {
        if (!Set.of("VIEW", "EXPORT", "DRILL", "EDIT").contains(action)) throw new IllegalArgumentException("Unknown business action");
        boolean dataset = "DATASET".equals(facts.resourceType());
        if (dataset && "EDIT".equals(action)) throw new IllegalArgumentException("Datasets do not accept resource EDIT");
        if (!Set.of("DATASET", "DASHBOARD", "TEMPLATE", "SCHOOL_COPY").contains(facts.resourceType()) || facts.tenantId() <= 0 || facts.userId() <= 0 || facts.identityEpoch() <= 0 || facts.accessEpoch() <= 0
                || facts.resourceId() <= 0 || facts.resourceVersion() <= 0) return new Result(false, "INVALID_FACTS", List.of(), List.of());
        if ("VIEW".equals(action) && facts.platformView()) {
            Scope viewing = platformView(facts);
            boolean allowed = dataset || "SCHOOL_COPY".equals(facts.resourceType()) ? !viewing.schools().isEmpty() : viewing.resource();
            return new Result(allowed, "PLATFORM_GROUP_VIEW", ids(viewing.schools()), List.of());
        }
        if (!facts.memberActive()) return new Result(false, "MEMBERSHIP_UNAVAILABLE", List.of(), List.of());
        Scope requested = scope(facts, action);
        if (!requested.complete()) return new Result(false, "POLICY_FACT_BUDGET_EXCEEDED", List.of(), List.of());
        var schools = new HashSet<>(requested.schools());
        boolean resource = requested.resource();
        var sources = new ArrayList<>(requested.sources());
        if (!"VIEW".equals(action)) {
            // Other operations require their own ordinary grants; VIEW uses the actual effective viewing qualification.
            Scope view = facts.platformView() ? platformView(facts) : scope(facts, "VIEW");
            if (!view.complete()) return new Result(false, "POLICY_FACT_BUDGET_EXCEEDED", List.of(), List.of());
            schools.retainAll(view.schools()); resource &= view.resource(); sources.addAll(view.sources());
        }
        boolean allowed = dataset || "SCHOOL_COPY".equals(facts.resourceType()) ? !schools.isEmpty() : resource;
        return new Result(allowed, allowed ? "POLICY_ALLOW" : "DEFAULT_OR_EXPLICIT_DENY", ids(schools), sources);
    }
    private Scope platformView(Facts facts) {
        if ("DATASET".equals(facts.resourceType())) return new Scope(false, facts.activeSchools(), List.of(), true);
        if ("SCHOOL_COPY".equals(facts.resourceType())) {
            Set<Long> schools = facts.resourceSchool() != null && facts.activeSchools().contains(facts.resourceSchool())
                    ? Set.of(facts.resourceSchool()) : Set.of();
            return new Scope(false, schools, List.of(), true);
        }
        return new Scope(true, Set.of(), List.of(), true);
    }
    private Scope scope(Facts f, String action) {
        var allows = new HashSet<Long>(); var denies = new HashSet<Long>();
        boolean allowResource = false, denyResource = false;
        var sources = new ArrayList<Source>();
        int sourceSchools = 0;
        for (var binding : f.bindings()) {
            var assignment = new HashSet<>(binding.assignmentSchools()); assignment.retainAll(f.activeSchools());
            if ("ROLE".equals(binding.subjectType()) && (binding.assignmentId() == null || assignment.isEmpty())) continue;
            for (var rule : binding.rules()) {
                if (!action.equals(rule.action())) continue;
                if (!Set.of("ALLOW", "DENY").contains(rule.effect())) throw new IllegalArgumentException("Invalid rule effect");
                var matched = new HashSet<Long>();
                switch (rule.schoolKind()) {
                    case "NONE" -> { }
                    case "EXPLICIT" -> matched.addAll(rule.schools());
                    case "ALL_ACTIVE_IN_TENANT" -> matched.addAll(f.activeSchools());
                    case "ASSIGNMENT" -> { if (!"ROLE".equals(binding.subjectType())) throw new IllegalArgumentException("Assignment on non-role"); matched.addAll(assignment); }
                    default -> throw new IllegalArgumentException("Invalid rule scope");
                }
                matched.retainAll(f.activeSchools());
                if ("ROLE".equals(binding.subjectType()) && !"NONE".equals(rule.schoolKind())) matched.retainAll(assignment);
                if ("SCHOOL_COPY".equals(f.resourceType())) matched.retainAll(f.resourceSchool() == null ? Set.of() : Set.of(f.resourceSchool()));
                boolean resource = "NONE".equals(rule.schoolKind()) && !"DATASET".equals(f.resourceType()) && !"SCHOOL_COPY".equals(f.resourceType());
                if (!resource && matched.isEmpty()) continue;
                sourceSchools += matched.size();
                if (sourceSchools > 10000 || sources.size() >= 1000) return new Scope(false, Set.of(), List.of(), false);
                if ("ALLOW".equals(rule.effect())) { allows.addAll(matched); allowResource |= resource; }
                else { denies.addAll(matched); denyResource |= resource; }
                sources.add(new Source(Long.toString(rule.id()), Long.toString(rule.version()), binding.subjectType(),
                        Long.toString(binding.subjectRef()), binding.assignmentId() == null ? null : binding.assignmentId().toString(),
                        action, rule.effect(), ids(matched)));
            }
        }
        allows.removeAll(denies);
        return new Scope(allowResource && !denyResource, Set.copyOf(allows), List.copyOf(sources), true);
    }
    private static List<String> ids(Set<Long> values) { return values.stream().sorted().map(Object::toString).toList(); }
}
