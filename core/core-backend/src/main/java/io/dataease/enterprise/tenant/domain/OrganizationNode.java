package io.dataease.enterprise.tenant.domain;

/** Immutable metadata facts; this is neither a JPA entity nor an authorization grant. */
public record OrganizationNode(long id, long tenantId, Long parentId, Kind kind, String name,
                               String schoolCode, Long schoolId, Status status, long version) {
    public enum Kind { SCHOOL, DEPARTMENT }
    public enum Status { DISABLED, ACTIVE }
}
