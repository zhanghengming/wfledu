package io.dataease.enterprise.management.server;

import io.dataease.api.permissions.enterprise.ResourceOwnershipApi;
import io.dataease.api.permissions.enterprise.ManagementContract.*;
import io.dataease.enterprise.management.manage.ResourceOwnershipService;
import io.dataease.result.ResultMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;

@RestController @ConditionalOnProperty(name="enterprise.management.enabled",havingValue="true")
public class ResourceOwnershipServer implements ResourceOwnershipApi {
    private final ResourceOwnershipService resources;
    private final HttpServletRequest http;
    public ResourceOwnershipServer(ResourceOwnershipService resources,HttpServletRequest http){this.resources=resources;this.http=http;}
    @Override public ResultMessage createResource(ResourceCreate request){return ResultMessage.success(resources.create(IdentityManagementServer.principal(http),ManagementFields.text(request.name(),128)));}
    @Override public ResultMessage readResource(ResourceRead request){return ResultMessage.success(resources.read(IdentityManagementServer.principal(http),ManagementFields.id(request.id()),request.action()));}
}
