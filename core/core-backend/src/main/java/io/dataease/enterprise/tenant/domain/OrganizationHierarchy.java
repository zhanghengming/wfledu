package io.dataease.enterprise.tenant.domain;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;

import java.util.HashSet;
import java.util.Objects;
import java.util.function.Function;

/** Tenant-scoped loaders supply current facts; no persistence or HTTP dependencies. */
public final class OrganizationHierarchy {
    private final int maximumDepth;

    public OrganizationHierarchy(int maximumDepth) {
        if (maximumDepth < 1) throw new IllegalArgumentException("A positive traversal budget is required");
        this.maximumDepth = maximumDepth;
    }

    public static void requireText(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > maximumCodePoints) invalid();
        if (space(value.codePointAt(0)) || space(value.codePointBefore(value.length()))) invalid();
        if (value.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.SURROGATE)) invalid();
    }

    private static boolean space(int code) {
        return Character.isWhitespace(code) || Character.isSpaceChar(code);
    }

    public void validate(OrganizationNode candidate, Function<Long, OrganizationNode> loader) {
        shape(candidate);
        if (candidate.schoolId() != null) {
            var school = load(candidate, candidate.schoolId(), loader);
            if (school.kind() != OrganizationNode.Kind.SCHOOL) invalid();
            // Validate the referenced school's own path as well; don't trust its cached kind alone.
            walk(school, loader, false);
        }
        walk(candidate, loader, false);
    }

    public void requireAvailable(OrganizationNode candidate, Function<Long, OrganizationNode> loader) {
        validate(candidate, loader);
        walk(candidate, loader, true);
        if (candidate.schoolId() != null) walk(load(candidate, candidate.schoolId(), loader), loader, true);
    }

    private void walk(OrganizationNode start, Function<Long, OrganizationNode> loader, boolean available) {
        var visited = new HashSet<Long>();
        OrganizationNode current = start;
        for (int depth = 0; ; depth++) {
            if (depth >= maximumDepth || !visited.add(current.id())) invalid();
            shape(current);
            if (current.tenantId() != start.tenantId()) missing();
            if (available && current.status() != OrganizationNode.Status.ACTIVE) denied();
            if (current.parentId() == null) return;
            var parent = load(start, current.parentId(), loader);
            Long scope = current.kind() == OrganizationNode.Kind.SCHOOL ? null : current.schoolId();
            Long parentScope = parent.kind() == OrganizationNode.Kind.SCHOOL ? Long.valueOf(parent.id()) : parent.schoolId();
            if (!Objects.equals(scope, parentScope)) invalid();
            current = parent;
        }
    }

    private static OrganizationNode load(OrganizationNode candidate, Long id, Function<Long, OrganizationNode> loader) {
        var target = loader.apply(id);
        if (target == null || target.id() != id || target.tenantId() != candidate.tenantId()) missing();
        return target;
    }

    private static void shape(OrganizationNode node) {
        if (node == null || node.id() <= 0 || node.tenantId() <= 0 || node.version() <= 0
                || node.kind() == null || node.status() == null) invalid();
        requireText(node.name(), 128);
        if (node.parentId() != null && (node.parentId() <= 0 || node.parentId() == node.id())) invalid();
        if (node.schoolId() != null && (node.schoolId() <= 0 || node.schoolId() == node.id())) invalid();
        if (node.kind() == OrganizationNode.Kind.SCHOOL) {
            requireText(node.schoolCode(), 64);
            if (node.schoolId() != null) invalid();
        } else if (node.schoolCode() != null) invalid();
    }

    private static void invalid() { throw new DEException(ResultCode.PARAM_IS_INVALID.code(), ResultCode.PARAM_IS_INVALID.message()); }
    private static void missing() { throw new DEException(ResultCode.RESOURCE_NOT_EXIST.code(), ResultCode.RESOURCE_NOT_EXIST.message()); }
    private static void denied() { throw new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message()); }
}
