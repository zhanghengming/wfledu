package io.dataease.enterprise.permission.server;

import io.dataease.api.permissions.enterprise.PermissionManagementApi;
import io.dataease.api.permissions.enterprise.PermissionContract.*;
import io.dataease.enterprise.permission.manage.PermissionBatchService;
import io.dataease.enterprise.permission.manage.PermissionReadService;
import io.dataease.enterprise.management.server.IdentityManagementServer;
import io.dataease.result.ResultMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "enterprise.management.enabled", havingValue = "true")
public class PermissionManagementServer implements PermissionManagementApi {
    private final io.dataease.enterprise.permission.manage.PermissionDecisionService decisions;
    private final PermissionReadService read;
    private final PermissionBatchService write;
    private final HttpServletRequest request;
    public PermissionManagementServer(PermissionReadService read, PermissionBatchService write, io.dataease.enterprise.permission.manage.PermissionDecisionService decisions, HttpServletRequest request) {
        this.decisions = decisions; this.read = read; this.write = write; this.request = request;
    }
    @Override public ResultMessage preview(Preview input) { return ResultMessage.success(decisions.preview(IdentityManagementServer.principal(request), input)); }
    @Override public ResultMessage catalog(Catalog input) { return ResultMessage.success(read.catalog(IdentityManagementServer.principal(request), input)); }
    @Override public ResultMessage rules(RulesPage input) { return ResultMessage.success(read.rules(IdentityManagementServer.principal(request), input)); }
    @Override public ResultMessage capabilities(CapabilitiesPage input) { return ResultMessage.success(read.capabilities(IdentityManagementServer.principal(request), input)); }
    @Override public ResultMessage permissions(Batch input) { return ResultMessage.success(write.permissions(IdentityManagementServer.principal(request), input)); }
    @Override public ResultMessage capabilitiesBatch(CapabilityBatch input) { return ResultMessage.success(write.capabilities(IdentityManagementServer.principal(request), input)); }
}
