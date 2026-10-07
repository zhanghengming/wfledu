package io.dataease.enterprise.context;

/**
 * Immutable server-side access facts. Validate authentication and current revisions before binding.
 * This record validates shape only; it grants no resource, school or administrator permissions.
 * Never deserialize it from a browser request as proof of identity.
 */
public record AccessContext(long tenantId, long userId, long accessEpoch, long identityEpoch) {

    public AccessContext {
        if (tenantId <= 0 || userId <= 0 || accessEpoch <= 0 || identityEpoch <= 0) {
            throw new IllegalArgumentException("Access context IDs and revisions must be positive");
        }
    }
}
