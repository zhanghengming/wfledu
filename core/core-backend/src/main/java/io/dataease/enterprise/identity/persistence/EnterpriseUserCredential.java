package io.dataease.enterprise.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name = "de_ent_user_credential") @Getter @Setter
public class EnterpriseUserCredential extends FoundationRecord {
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(nullable=false,length=32) private String scheme = "PBKDF2_SHA256";
    @Column(name="encoded_hash",nullable=false,length=512) private String encodedHash;
    @Column(name="password_changed_at",nullable=false,columnDefinition="datetime(6)") private LocalDateTime passwordChangedAt;
    @Column(name="must_reset",nullable=false) private boolean mustReset = true;
    @Column(name="failed_attempts",nullable=false) private int failedAttempts;
    @Column(name="locked_until",columnDefinition="datetime(6)") private LocalDateTime lockedUntil;
}
