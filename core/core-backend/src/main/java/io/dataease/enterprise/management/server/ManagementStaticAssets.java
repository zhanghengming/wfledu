package io.dataease.enterprise.management.server;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.result.ResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Public packaged UI assets only. Never resolves an external path or forwards to an old API. */
final class ManagementStaticAssets {
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("js", "application/javascript"), Map.entry("css", "text/css"), Map.entry("svg", "image/svg+xml"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("ico", "image/x-icon"));
    private ManagementStaticAssets() { }

    static boolean serve(HttpServletRequest request, HttpServletResponse response, ObjectMapper json) throws IOException {
        String path = request.getRequestURI();
        boolean html = path.equals("/enterprise.html");
        boolean asset = (path.startsWith("/js/") || path.startsWith("/assets/"))
                && path.matches("/[A-Za-z0-9_./-]+") && !path.contains("..") && !path.contains("//")
                && TYPES.containsKey(path.substring(path.lastIndexOf('.') + 1));
        if (!html && !asset) return false;
        if (!Set.of("GET", "HEAD").contains(request.getMethod()) || request.getQueryString() != null
                || request.getDispatcherType() != DispatcherType.REQUEST || !request.getContextPath().isEmpty())
            throw new DEException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        response.setHeader("X-Frame-Options", "DENY");
        var resource = new ClassPathResource("static" + path);
        if (!resource.exists() || !resource.isReadable()) {
            response.setStatus(404); response.setContentType("application/json");
            if (!request.getMethod().equals("HEAD")) json.writeValue(response.getOutputStream(), ResultMessage.failure(ResultCode.RESOURCE_NOT_EXIST));
            return true;
        }
        response.setContentType(html ? "text/html;charset=UTF-8" : TYPES.get(path.substring(path.lastIndexOf('.') + 1)));
        response.setContentLengthLong(resource.contentLength());
        if (!request.getMethod().equals("HEAD")) try (var input = resource.getInputStream()) { input.transferTo(response.getOutputStream()); }
        return true;
    }
}
