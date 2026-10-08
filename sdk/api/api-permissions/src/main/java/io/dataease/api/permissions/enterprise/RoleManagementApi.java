package io.dataease.api.permissions.enterprise;

import io.dataease.result.ResultMessage;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import static io.dataease.api.permissions.enterprise.ManagementContract.*;

@RequestMapping("/api/enterprise/v1")
public interface RoleManagementApi {
    @Operation(summary = "分页读取当前集团角色")
    @PostMapping("/roles/page") ResultMessage roles(@RequestBody Page request);
    @Operation(summary = "保存当前集团角色")
    @PostMapping("/roles/save") ResultMessage saveRole(@RequestBody RoleSave request);
    @Operation(summary = "分页读取学校与角色配对任职")
    @PostMapping("/assignments/page") ResultMessage assignments(@RequestBody AssignmentPage request);
    @Operation(summary = "保存学校与角色配对任职")
    @PostMapping("/assignments/save") ResultMessage saveAssignment(@RequestBody AssignmentSave request);
}
