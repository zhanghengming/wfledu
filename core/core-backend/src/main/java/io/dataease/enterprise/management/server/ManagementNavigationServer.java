package io.dataease.enterprise.management.server;

import io.dataease.api.permissions.enterprise.ManagementNavigationApi;
import io.dataease.api.permissions.enterprise.ManagementContract.*;
import io.dataease.enterprise.management.manage.ManagementNavigationService;
import io.dataease.result.ResultMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "enterprise.management.enabled", havingValue = "true")
public class ManagementNavigationServer implements ManagementNavigationApi {
    private final ManagementNavigationService navigation;
    private final HttpServletRequest request;

    public ManagementNavigationServer(ManagementNavigationService navigation, HttpServletRequest request) {
        this.navigation = navigation;
        this.request = request;
    }

    @Override public ResultMessage tenants(Page input) {
        return ResultMessage.success(navigation.tenants(IdentityManagementServer.principal(request),
                ManagementFields.page(input.pageNum()), ManagementFields.size(input.pageSize())));
    }
    @Override public ResultMessage navigation(Empty input) {
        return ResultMessage.success(navigation.navigation(IdentityManagementServer.principal(request)));
    }
}
