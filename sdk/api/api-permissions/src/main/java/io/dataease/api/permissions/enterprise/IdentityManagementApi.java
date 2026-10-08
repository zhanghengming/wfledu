package io.dataease.api.permissions.enterprise;

import io.dataease.result.ResultMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import static io.dataease.api.permissions.enterprise.ManagementContract.*;

@RequestMapping("/api/enterprise/v1")
public interface IdentityManagementApi {
    @PostMapping("/auth/login") ResultMessage login(@RequestBody Login request);
    @PostMapping("/auth/password") ResultMessage password(@RequestBody PasswordChange request);
    @PostMapping("/auth/logout") ResultMessage logout(@RequestBody Empty request);
    @PostMapping("/context/current") ResultMessage current(@RequestBody Empty request);
    @PostMapping("/context/switch") ResultMessage switchContext(@RequestBody Switch request);
}
