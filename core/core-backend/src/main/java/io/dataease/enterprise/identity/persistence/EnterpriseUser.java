package io.dataease.enterprise.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "de_ent_user")
public class EnterpriseUser extends FoundationRecord {
    @Column(name = "username", length = 128, nullable = false, updatable = false)
    private String username;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private FoundationStatus status = FoundationStatus.DISABLED;

    @Column(name = "identity_epoch", nullable = false)
    private Long identityEpoch = 1L;

    @Column(name = "legacy_uid")
    private Long legacyUid;
}
