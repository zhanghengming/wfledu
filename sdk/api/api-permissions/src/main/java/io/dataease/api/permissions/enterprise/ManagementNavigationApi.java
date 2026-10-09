package io.dataease.api.permissions.enterprise;

import io.dataease.result.ResultMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import static io.dataease.api.permissions.enterprise.ManagementContract.*;

/** Current caller navigation, independent of business resource permissions. */
@RequestMapping("/api/enterprise/v1/context")
public interface ManagementNavigationApi {
    @PostMapping("/tenants") ResultMessage tenants(@RequestBody Page request);
    @PostMapping("/navigation") ResultMessage navigation(@RequestBody Empty request);
}
