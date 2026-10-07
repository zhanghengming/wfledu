package io.dataease.enterprise.context;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;

import java.util.Objects;
import java.util.Optional;

/**
 * Explicit, non-inheritable context scope. Authentication adapters own binding; consumers only read.
 * Every binding must use try-with-resources, including explicit bindings in asynchronous jobs.
 * A captured context is not a substitute for checking current authorization on execution/delivery.
 */
public final class AccessContextHolder {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private AccessContextHolder() {
    }

    public static Optional<AccessContext> current() {
        Scope scope = CURRENT.get();
        return scope == null ? Optional.empty() : Optional.of(scope.context);
    }

    public static AccessContext requireCurrent() {
        return current().orElseThrow(() -> new DEException(
                ResultCode.USER_NOT_LOGGED_IN.code(), ResultCode.USER_NOT_LOGGED_IN.message()));
    }

    public static Scope open(AccessContext context) {
        Objects.requireNonNull(context, "Access context is required");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("An access context is already bound to this thread");
        }
        Scope scope = new Scope(context, Thread.currentThread());
        CURRENT.set(scope);
        return scope;
    }

    public static final class Scope implements AutoCloseable {

        private final AccessContext context;
        private final Thread owner;
        private boolean closed;

        private Scope(AccessContext context, Thread owner) {
            this.context = context;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("An access context scope must be closed by its owning thread");
            }
            if (closed) {
                return;
            }
            if (CURRENT.get() != this) {
                throw new IllegalStateException("The access context scope no longer owns this thread");
            }
            CURRENT.remove();
            closed = true;
        }
    }
}
