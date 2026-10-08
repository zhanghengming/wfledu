package io.dataease.enterprise.bootstrap;

import io.dataease.enterprise.context.ManagementRequestBridge;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ManagementRequestBridgeTest {
    private static final String PATH="/de2api/api/enterprise/v1/context/current";
    @Test void headersAndPathsAloneNeverBypassNativeAuthentication(){var request=new MockHttpServletRequest("POST",PATH);request.addHeader("X-Tenant-Id","10");assertThat(ManagementRequestBridge.permits(PATH)).isFalse();}
    @Test void activeScopeMatchesOnlyTheExactRequestAndClearsAfterException(){
        var request=new MockHttpServletRequest("POST",PATH);
        assertThatThrownBy(()->{try(var scope=ManagementRequestBridge.open(request)){
            assertThat(ManagementRequestBridge.permits(PATH)).isTrue();assertThat(ManagementRequestBridge.permits("/api/enterprise/v1/context/current")).isTrue();
            assertThat(ManagementRequestBridge.permits("/de2api/datasetData/preview")).isFalse();throw new IllegalStateException("Synthetic request failure");
        }}).hasMessage("Synthetic request failure");assertThat(ManagementRequestBridge.permits(PATH)).isFalse();
    }
    @Test void nestedOrOtherThreadScopesAndNonManagementPathsReject() throws Exception {
        var request=new MockHttpServletRequest("POST",PATH);
        try(var scope=ManagementRequestBridge.open(request)){
            assertThatThrownBy(()->ManagementRequestBridge.open(request)).isInstanceOf(IllegalStateException.class);
            try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
                assertThat(pool.submit(()->ManagementRequestBridge.permits(PATH)).get()).isFalse();
                assertThat(pool.submit(()->{try{scope.close();return false;}catch(IllegalStateException failure){return true;}}).get()).isTrue();
            }
        }
        assertThat(ManagementRequestBridge.permits(PATH)).isFalse();
        assertThatThrownBy(()->ManagementRequestBridge.open(new MockHttpServletRequest("POST","/de2api/datasetData/preview"))).isInstanceOf(IllegalArgumentException.class);
    }
}
