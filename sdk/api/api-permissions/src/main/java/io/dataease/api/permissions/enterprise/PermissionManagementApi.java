package io.dataease.api.permissions.enterprise;

import io.dataease.result.ResultMessage;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import static io.dataease.api.permissions.enterprise.PermissionContract.*;

@RequestMapping("/api/enterprise/v1")
public interface PermissionManagementApi {
    @Operation(summary = "读取本集团可配置授权的学校与资源元数据")
    @PostMapping("/permissions/catalog") ResultMessage catalog(@RequestBody Catalog request);
    @Operation(summary = "分页读取主体业务规则或同修订的完整学校集合")
    @PostMapping("/permissions/rules/page") ResultMessage rules(@RequestBody RulesPage request);
    @Operation(summary = "同主体原子配置普通业务授权")
    @PostMapping("/permissions/batch") ResultMessage permissions(@RequestBody Batch request);
    @Operation(summary = "分页读取主体集团管理能力")
    @PostMapping("/admin-capabilities/page") ResultMessage capabilities(@RequestBody CapabilitiesPage request);
    @Operation(summary = "同主体原子配置集团管理能力")
    @PostMapping("/admin-capabilities/batch") ResultMessage capabilitiesBatch(@RequestBody CapabilityBatch request);
}
