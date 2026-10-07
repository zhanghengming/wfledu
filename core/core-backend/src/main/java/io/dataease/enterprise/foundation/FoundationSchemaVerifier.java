package io.dataease.enterprise.foundation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/** Read-only validation after InitSqlListener, including previously successful migrations. */
public final class FoundationSchemaVerifier implements ApplicationRunner, Ordered {
    private final EnterpriseFoundationSqlBlock block;

    public FoundationSchemaVerifier(EnterpriseFoundationSqlBlock block) {
        this.block = block;
    }

    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public void run(ApplicationArguments args) {
        block.verifySchema();
    }
}
