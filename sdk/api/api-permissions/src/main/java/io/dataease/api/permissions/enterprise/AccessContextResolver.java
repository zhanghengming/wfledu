package io.dataease.api.permissions.enterprise;

import io.dataease.enterprise.context.AccessContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolve user and current group from a verified server-side session.
 * Plain browser tenant/user/role fields are never identity evidence. Validate membership,
 * identity status and current security revisions before returning; reject missing, expired
 * or ambiguous identity with the existing authentication error. This does not grant data access.
 */
public interface AccessContextResolver {

    AccessContext resolveAuthenticated(HttpServletRequest request);
}
