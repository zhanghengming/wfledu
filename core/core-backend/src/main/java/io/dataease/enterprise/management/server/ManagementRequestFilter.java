package io.dataease.enterprise.management.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.enterprise.bootstrap.ManagementReadiness;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.context.ManagementRequestBridge;
import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.result.ResultMessage;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Opt-in management mode closes legacy business, public share, file and plugin routes. */
public final class ManagementRequestFilter implements Filter {
    public static final String PRINCIPAL_ATTRIBUTE=ManagementRequestFilter.class.getName()+".principal";
    private static final String PREFIX="/de2api/api/enterprise/v1/";
    private static final Set<String> ROUTES=Set.of("ready","auth/login","auth/password","auth/logout","context/current","context/switch",
            "tenants/page","tenants/create","users/create","members/page","members/save","organizations/page","organizations/save","organizations/school","resources/create","resources/read","roles/page","roles/save","assignments/page","assignments/save","permissions/catalog","permissions/rules/page","permissions/batch","admin-capabilities/page","admin-capabilities/batch");
    private final ManagementSessionService sessions;
    private final ManagementReadiness readiness;
    private final Set<String> origins;
    private final ObjectMapper json=new ObjectMapper();
    private final ManagementAccessContextResolver resolver;
    public ManagementRequestFilter(ManagementSessionService sessions,ManagementReadiness readiness,Set<String> origins,ManagementAccessContextResolver resolver){this.sessions=sessions;this.readiness=readiness;this.origins=Set.copyOf(origins);this.resolver=resolver;}
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var request=(HttpServletRequest)input;var response=new ManagementResponse((HttpServletResponse)output);
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        try {
            String path=request.getRequestURI();
            if(!readiness.ready()){fail(response,503,ResultCode.INTERFACE_REQUEST_TIMEOUT);return;}
            if(!path.startsWith(PREFIX) || path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//")
                    || !request.getContextPath().isEmpty() || request.getQueryString()!=null || !ROUTES.contains(path.substring(PREFIX.length()))
                    || request.getDispatcherType()!=jakarta.servlet.DispatcherType.REQUEST)throw denied();
            var originHeaders=Collections.list(request.getHeaders("Origin"));
            if(originHeaders.size()>1 || originHeaders.size()==1 && !origins.contains(originHeaders.getFirst()))throw denied();
            if(path.equals(PREFIX+"ready") && request.getMethod().equals("GET")){response.setContentType("application/json");json.writeValue(response.getOutputStream(),ResultMessage.success());return;}
            if(!request.getMethod().equals("POST") || request.getHeader("Content-Encoding")!=null || request.getContentLengthLong()>65536)throw ManagementFields.invalid();
            MediaType content;
            try{content=MediaType.parseMediaType(request.getContentType());}catch(IllegalArgumentException failure){throw ManagementFields.invalid();}
            if(!MediaType.APPLICATION_JSON.isCompatibleWith(content) || content.getCharset()!=null && !content.getCharset().equals(StandardCharsets.UTF_8))throw ManagementFields.invalid();
            for(String header:List.of("X-DE-TOKEN","X-EMBEDDED-TOKEN","X-DE-LINK-TOKEN","X-Enterprise-Session","X-Tenant-Id","X-User-Id"))
                if(request.getHeader(header)!=null)throw denied();
            var authorization=Collections.list(request.getHeaders("Authorization"));
            if(path.equals(PREFIX+"auth/login")){
                if(!authorization.isEmpty())throw denied();
                try(var bridge=ManagementRequestBridge.open(request)){chain.doFilter(request,response);}return;
            }
            if(authorization.size()!=1 || !authorization.getFirst().matches("Bearer [A-Za-z0-9_-]{43}"))throw unauthenticated();
            var principal=sessions.authenticate(authorization.getFirst().substring(7));
            if(principal.mustReset() && !path.equals(PREFIX+"auth/password") && !path.equals(PREFIX+"auth/logout"))throw denied();
            request.setAttribute(PRINCIPAL_ATTRIBUTE,principal);
            try(var bridge=ManagementRequestBridge.open(request)) {
                if(path.startsWith(PREFIX+"members/") || path.startsWith(PREFIX+"organizations/") || path.startsWith(PREFIX+"resources/") || path.startsWith(PREFIX+"roles/") || path.startsWith(PREFIX+"assignments/") || path.startsWith(PREFIX+"permissions/") || path.startsWith(PREFIX+"admin-capabilities/")) {
                    try(var scope=AccessContextHolder.open(resolver.resolveAuthenticated(request))){chain.doFilter(request,response);}
                } else chain.doFilter(request,response);
            } finally {request.removeAttribute(PRINCIPAL_ATTRIBUTE);}
        } catch(DEException failure){
            if(response.isCommitted())throw failure;
            response.setStatus(failure.getCode()==20001?401:failure.getCode()==70001?403:400);response.setContentType("application/json");
            json.writeValue(response.getOutputStream(),new ResultMessage(failure.getCode(),failure.getMsg()));
        }
    }
    private void fail(HttpServletResponse response,int status,ResultCode code) throws IOException{response.setStatus(status);response.setContentType("application/json");json.writeValue(response.getOutputStream(),ResultMessage.failure(code));}
    private static DEException denied(){return new DEException(ResultCode.PERMISSION_NO_ACCESS.code(),ResultCode.PERMISSION_NO_ACCESS.message());}
    private static DEException unauthenticated(){return new DEException(ResultCode.USER_NOT_LOGGED_IN.code(),ResultCode.USER_NOT_LOGGED_IN.message());}
}
