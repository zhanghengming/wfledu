package io.dataease.enterprise.permission.persistence;

import io.dataease.enterprise.identity.persistence.FoundationRecord;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "de_ent_idempotency")
public class EnterpriseIdempotency extends FoundationRecord {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;
    @Column(name = "principal_kind", nullable = false, updatable = false, length = 8)
    private String principalKind = "USER";
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "app_id", updatable = false)
    private Long appId;
    @Setter(AccessLevel.NONE)
    @Column(name = "principal_key", insertable = false, updatable = false)
    private Long principalKey;
    @Column(name = "operation", nullable = false, updatable = false, length = 64)
    private String operation;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(name = "request_digest", nullable = false, updatable = false, columnDefinition = "binary(32)")
    private byte[] requestDigest;
    @Column(name = "response_ref", length = 255)
    private String responseRef;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_metadata", columnDefinition = "json")
    private String resultMetadata;
    @Column(name = "state", nullable = false, length = 16)
    private String state = "IN_PROGRESS";
    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private LocalDateTime expiresAt;

    public void setRequestDigest(byte[] value) {
        if (value == null || value.length != 32) throw new IllegalArgumentException("SHA256 digest must have exactly 32 bytes");
        this.requestDigest=value.clone();
    }
    public byte[] getRequestDigest() { return requestDigest == null ? null : requestDigest.clone(); }
    @PrePersist
    @PreUpdate
    private void requireDigest() {
        if (requestDigest == null || requestDigest.length != 32) throw new IllegalStateException("SHA256 digest must have exactly 32 bytes");
    }
}
