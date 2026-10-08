package io.dataease.enterprise.management.server;

import io.dataease.api.permissions.enterprise.AccessContextResolver;
import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.identity.manage.ManagementSessionService;
import jakarta.servlet.http.HttpServletRequest;

/** Only a verified request attribute from the management filter can supply identity. */
public final class ManagementAccessContextResolver implements AccessContextResolver {
    private final ManagementSessionService sessions;
    public ManagementAccessContextResolver(ManagementSessionService sessions){this.sessions=sessions;}
    @Override public AccessContext resolveAuthenticated(HttpServletRequest request){return sessions.access(IdentityManagementServer.principal(request));}
}
