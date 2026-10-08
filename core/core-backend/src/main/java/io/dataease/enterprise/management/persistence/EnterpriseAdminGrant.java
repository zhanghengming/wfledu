package io.dataease.enterprise.management.persistence;

import io.dataease.enterprise.identity.persistence.FoundationRecord;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "de_ent_admin_grant")
public class EnterpriseAdminGrant extends FoundationRecord {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "capability", nullable = false, length = 32)
    private String capability;

    @Column(name = "effect", nullable = false, length = 8)
    private String effect;

    @Enumerated(EnumType.STRING)

    @Column(name = "status", nullable = false, length = 16)
    private FoundationStatus status = FoundationStatus.DISABLED;
}
