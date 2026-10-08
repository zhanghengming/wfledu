package io.dataease.enterprise.management.server;

import io.dataease.api.permissions.enterprise.RoleManagementApi;
import io.dataease.api.permissions.enterprise.ManagementContract.*;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.enterprise.management.manage.RoleManagementService;
import io.dataease.result.ResultMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "enterprise.management.enabled", havingValue = "true")
public class RoleManagementServer implements RoleManagementApi {
    private final RoleManagementService service;
    private final HttpServletRequest request;
    public RoleManagementServer(RoleManagementService service, HttpServletRequest request) { this.service = service; this.request = request; }
    @Override public ResultMessage roles(Page input) { return ResultMessage.success(service.roles(IdentityManagementServer.principal(request),
            ManagementFields.page(input.pageNum()), ManagementFields.size(input.pageSize()))); }
    @Override public ResultMessage assignments(AssignmentPage input) { return ResultMessage.success(service.assignments(IdentityManagementServer.principal(request),
            ManagementFields.optionalId(input.memberId()), ManagementFields.optionalId(input.roleId()), ManagementFields.optionalId(input.schoolId()),
            ManagementFields.page(input.pageNum()), ManagementFields.size(input.pageSize()))); }
    @Override public ResultMessage saveRole(RoleSave input) { return ResultMessage.success(service.saveRole(IdentityManagementServer.principal(request),
            new RoleManagementService.RoleSave(create(input.mode()), ManagementFields.optionalId(input.id()), ManagementFields.optionalId(input.expectedVersion()),
                    input.code(), input.name(), status(input.status())))); }
    @Override public ResultMessage saveAssignment(AssignmentSave input) {
        if (input.schoolIds() == null) throw ManagementFields.invalid();
        return ResultMessage.success(service.saveAssignment(IdentityManagementServer.principal(request),
                new RoleManagementService.AssignmentSave(create(input.mode()), ManagementFields.optionalId(input.id()), ManagementFields.optionalId(input.expectedVersion()),
                        ManagementFields.id(input.memberId()), ManagementFields.id(input.roleId()), input.schoolIds().stream().map(ManagementFields::id).toList(), status(input.status()))));
    }
    private static boolean create(String value) { if ("CREATE".equals(value)) return true; if ("UPDATE".equals(value)) return false; throw ManagementFields.invalid(); }
    private static FoundationStatus status(String value) {
        try { return FoundationStatus.valueOf(value); } catch (IllegalArgumentException | NullPointerException failure) { throw ManagementFields.invalid(); }
    }
}
