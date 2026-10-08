package io.dataease.enterprise.management.server;

import io.dataease.api.permissions.enterprise.IdentityManagementApi;
import io.dataease.api.permissions.enterprise.ManagementContract.*;
import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.manage.ManagementSessionService.Principal;
import io.dataease.result.ResultMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(name="enterprise.management.enabled",havingValue="true")
public class IdentityManagementServer implements IdentityManagementApi {
    private final ManagementSessionService sessions;
    private final HttpServletRequest request;
    public IdentityManagementServer(ManagementSessionService sessions,HttpServletRequest request){this.sessions=sessions;this.request=request;}
    @Override public ResultMessage login(Login input){
        var result=sessions.login(input.username(),input.password());var data=new LinkedHashMap<>(projection(result.principal()));data.put("credential",result.credential());return ResultMessage.success(data);
    }
    @Override public ResultMessage password(PasswordChange input){sessions.changePassword(principal(request),input.previousPassword(),input.newPassword());return ResultMessage.success();}
    @Override public ResultMessage logout(Empty input){sessions.logout(principal(request));return ResultMessage.success();}
    @Override public ResultMessage current(Empty input){return ResultMessage.success(projection(principal(request)));}
    @Override public ResultMessage switchContext(Switch input){return ResultMessage.success(projection(sessions.switchTenant(principal(request),ManagementFields.id(input.tenantId()),ManagementFields.id(input.expectedVersion()))));}
    public static Principal principal(HttpServletRequest request){
        Object value=request.getAttribute(ManagementRequestFilter.PRINCIPAL_ATTRIBUTE);
        if(!(value instanceof Principal principal))throw new io.dataease.exception.DEException(io.dataease.result.ResultCode.USER_NOT_LOGGED_IN.code(),io.dataease.result.ResultCode.USER_NOT_LOGGED_IN.message());
        return principal;
    }
    private static Map<String,Object> projection(Principal principal){
        Map<String,Object> data=new LinkedHashMap<>();data.put("sessionId",Long.toString(principal.sessionId()));data.put("version",Long.toString(principal.version()));
        data.put("userId",Long.toString(principal.userId()));data.put("tenantId",principal.tenantId()==null?null:Long.toString(principal.tenantId()));data.put("mustReset",principal.mustReset());return data;
    }
}
