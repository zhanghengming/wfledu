package io.dataease.enterprise.management.server;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/** Management credentials and metadata remain non-cacheable through downstream interceptors. */
public final class ManagementResponse extends HttpServletResponseWrapper {
    public ManagementResponse(HttpServletResponse response) {
        super(response);
        secureHeaders();
    }
    @Override public void setHeader(String name, String value) {
        super.setHeader(name, protectedValue(name, value));
    }
    @Override public void addHeader(String name, String value) {
        if (protectedHeader(name)) setHeader(name, value);
        else super.addHeader(name, value);
    }
    @Override public void reset() {
        super.reset();
        secureHeaders();
    }
    private void secureHeaders() {
        setHeader("Cache-Control", "no-store");
        setHeader("X-Content-Type-Options", "nosniff");
    }
    private static boolean protectedHeader(String name) {
        return "Cache-Control".equalsIgnoreCase(name) || "X-Content-Type-Options".equalsIgnoreCase(name);
    }
    private static String protectedValue(String name, String value) {
        if ("Cache-Control".equalsIgnoreCase(name)) return "no-store";
        if ("X-Content-Type-Options".equalsIgnoreCase(name)) return "nosniff";
        return value;
    }
}
