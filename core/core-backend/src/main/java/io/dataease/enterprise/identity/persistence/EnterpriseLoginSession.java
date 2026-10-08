package io.dataease.enterprise.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name="de_ent_login_session") @Getter @Setter
public class EnterpriseLoginSession {
    @Id @Column(nullable=false,updatable=false) private Long id;
    @Version @Column(nullable=false) private Long version = 1L;
    @Column(name="created_at",nullable=false,updatable=false,columnDefinition="datetime(6)") private LocalDateTime createdAt;
    @Column(name="expires_at",nullable=false,columnDefinition="datetime(6)") private LocalDateTime expiresAt;
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(name="token_hash",nullable=false,updatable=false,columnDefinition="binary(32)") private byte[] tokenHash;
    @Column(name="selected_tenant_id") private Long selectedTenantId;
    @Column(name="identity_epoch",nullable=false) private Long identityEpoch;
    @Column(name="last_seen_at",nullable=false,columnDefinition="datetime(6)") private LocalDateTime lastSeenAt;
    @Column(name="idle_expires_at",nullable=false,columnDefinition="datetime(6)") private LocalDateTime idleExpiresAt;
    @Column(nullable=false,length=16) private String status = "ACTIVE";
}
