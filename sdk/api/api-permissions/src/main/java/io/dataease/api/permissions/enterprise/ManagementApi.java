package io.dataease.api.permissions.enterprise;

import io.dataease.result.ResultMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import static io.dataease.api.permissions.enterprise.ManagementContract.*;

/** New API only; no changes to the community login or business interface semantics. */
@RequestMapping("/api/enterprise/v1")
public interface ManagementApi {
    @PostMapping("/tenants/page") ResultMessage tenants(@RequestBody Page request);
    @PostMapping("/tenants/create") ResultMessage createTenant(@RequestBody TenantCreate request);
    @PostMapping("/users/create") ResultMessage createUser(@RequestBody UserCreate request);
    @PostMapping("/members/page") ResultMessage members(@RequestBody Page request);
    @PostMapping("/members/save") ResultMessage saveMember(@RequestBody MemberSave request);
    @PostMapping("/organizations/page") ResultMessage organizations(@RequestBody Page request);
    @PostMapping("/organizations/save") ResultMessage saveOrganization(@RequestBody OrganizationSave request);
    @PostMapping("/organizations/school") ResultMessage school(@RequestBody Reference request);
}
