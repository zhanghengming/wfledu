package io.dataease.api.permissions.enterprise;

import io.dataease.result.ResultMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import static io.dataease.api.permissions.enterprise.ManagementContract.*;

@RequestMapping("/api/enterprise/v1")
public interface ResourceOwnershipApi {
    @PostMapping("/resources/create") ResultMessage createResource(@RequestBody ResourceCreate request);
    @PostMapping("/resources/read") ResultMessage readResource(@RequestBody ResourceRead request);
}
