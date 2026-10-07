package io.dataease.enterprise.context;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessContextHolderTest {

    private final AccessContext groupA = new AccessContext(101L, 1001L, 1L, 1L);
    private final AccessContext groupB = new AccessContext(202L, 1001L, 2L, 1L);

    @AfterEach
    void noContextSurvivesATest() {
        assertThat(AccessContextHolder.current()).isEmpty();
    }

    @Test
    void missingContextUsesExistingUnauthenticatedError() {
        assertThatThrownBy(AccessContextHolder::requireCurrent).isInstanceOf(DEException.class)
                .satisfies(error -> assertThat(((DEException) error).getCode())
                        .isEqualTo(ResultCode.USER_NOT_LOGGED_IN.code()));
    }

    @Test
    void invalidIdsAndRevisionsCannotFormContext() {
        for (long invalid : new long[]{0L, -1L}) {
            assertThatThrownBy(() -> new AccessContext(invalid, 1L, 1L, 1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AccessContext(1L, invalid, 1L, 1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AccessContext(1L, 1L, invalid, 1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AccessContext(1L, 1L, 1L, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AccessContextHolder.open(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void closedScopeRemovesContext() {
        try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupA)) {
            assertThat(AccessContextHolder.requireCurrent()).isEqualTo(groupA);
        }
        assertThat(AccessContextHolder.current()).isEmpty();
    }

    @Test
    void exceptionAlsoRemovesContext() {
        assertThatThrownBy(() -> {
            try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupA)) {
                throw new IllegalStateException("synthetic request failure");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(AccessContextHolder.current()).isEmpty();
    }

    @Test
    void nestedBindingCannotReplaceTheCurrentGroup() {
        try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupA)) {
            assertThatThrownBy(() -> AccessContextHolder.open(groupB)).isInstanceOf(IllegalStateException.class);
            assertThat(AccessContextHolder.requireCurrent()).isEqualTo(groupA);
        }
    }

    @Test
    void closingAnOldScopeCannotClearALaterScope() {
        AccessContextHolder.Scope old = AccessContextHolder.open(groupA);
        old.close();
        try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupB)) {
            old.close();
            assertThat(AccessContextHolder.requireCurrent()).isEqualTo(groupB);
        }
    }

    @Test
    void workerCannotInheritRequestContextAndReusedThreadIsClean() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupA)) {
            assertThat(executor.submit(() -> AccessContextHolder.current().isEmpty()).get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> {
                try (AccessContextHolder.Scope worker = AccessContextHolder.open(groupB)) {
                    assertThat(AccessContextHolder.requireCurrent()).isEqualTo(groupB);
                    throw new IllegalStateException("synthetic task failure");
                } catch (IllegalStateException expected) {
                    return AccessContextHolder.current().isEmpty();
                }
            }).get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> AccessContextHolder.current().isEmpty()).get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(AccessContextHolder.requireCurrent()).isEqualTo(groupA);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void asynchronousBindingIsExplicitAndThreadConfined() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(executor.submit(() -> {
                try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupA)) {
                    return AccessContextHolder.requireCurrent();
                }
            }).get(2, TimeUnit.SECONDS)).isEqualTo(groupA);
            assertThat(executor.submit(() -> AccessContextHolder.current().isEmpty()).get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anotherThreadCannotCloseTheOwnersScope() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AccessContextHolder.Scope scope = AccessContextHolder.open(groupA)) {
            assertThat(executor.submit(() -> {
                try {
                    scope.close();
                    return false;
                } catch (IllegalStateException expected) {
                    return true;
                }
            }).get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(AccessContextHolder.requireCurrent()).isEqualTo(groupA);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentGroupsStayInTheirOwnThreads() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bound = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupA)) {
                    bound.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("synthetic concurrency barrier timed out");
                    }
                    return AccessContextHolder.requireCurrent();
                }
            });
            var second = executor.submit(() -> {
                try (AccessContextHolder.Scope ignored = AccessContextHolder.open(groupB)) {
                    bound.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("synthetic concurrency barrier timed out");
                    }
                    return AccessContextHolder.requireCurrent();
                }
            });
            assertThat(bound.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(AccessContextHolder.current()).isEmpty();
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(groupA);
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(groupB);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
