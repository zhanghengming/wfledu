package io.dataease.enterprise.foundation;

import io.dataease.enterprise.management.server.ManagementResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class ManagementResponseTest {
    @Test void downstreamCacheOverridesAndResetCannotExposeCredentialsToCaches() {
        var original = new MockHttpServletResponse();
        var guarded = new ManagementResponse(original);
        guarded.setHeader("cache-control", "public,max-age=600");
        guarded.addHeader("Cache-Control", "no-cache");
        guarded.setHeader("X-Content-Type-Options", null);
        assertThat(original.getHeaders("Cache-Control")).containsExactly("no-store");
        assertThat(original.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        guarded.reset();
        assertThat(original.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(original.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        guarded.setStatus(401);
        guarded.setHeader("Content-Type", "application/json");
        assertThat(original.getStatus()).isEqualTo(401);
        assertThat(original.getContentType()).isEqualTo("application/json");
    }
}
