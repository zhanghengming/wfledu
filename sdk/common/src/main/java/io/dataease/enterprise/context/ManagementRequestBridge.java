package io.dataease.enterprise.context;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/** Trusted filter-owned scope; never enabled by a header, URL alone, or default mode. */
public final class ManagementRequestBridge {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static final String PREFIX="/de2api/api/enterprise/v1/";
    private ManagementRequestBridge(){ }
    public static Scope open(HttpServletRequest request){
        Objects.requireNonNull(request);
        if(CURRENT.get()!=null)throw new IllegalStateException("Management authentication scope already bound");
        String path=request.getRequestURI();
        if(!"POST".equals(request.getMethod()) || !path.startsWith(PREFIX) || path.contains("%") || path.contains(";") || path.contains(".") || path.contains("\\") || path.contains("//"))
            throw new IllegalArgumentException("Invalid management authentication scope");
        var scope=new Scope(path,Thread.currentThread());CURRENT.set(scope);return scope;
    }
    public static boolean permits(String uri){
        var scope=CURRENT.get();return scope!=null && scope.owner==Thread.currentThread()
                && (scope.path.equals(uri) || scope.path.substring("/de2api".length()).equals(uri));
    }
    public static final class Scope implements AutoCloseable {
        private final String path;private final Thread owner;private boolean closed;
        private Scope(String path,Thread owner){this.path=path;this.owner=owner;}
        @Override public void close(){
            if(Thread.currentThread()!=owner)throw new IllegalStateException("Management authentication scope belongs to another thread");
            if(closed)return;if(CURRENT.get()!=this)throw new IllegalStateException("Management authentication scope was replaced");
            CURRENT.remove();closed=true;
        }
    }
}
