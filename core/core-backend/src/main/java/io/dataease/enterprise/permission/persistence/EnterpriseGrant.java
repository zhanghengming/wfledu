package io.dataease.enterprise.permission.persistence;

import io.dataease.enterprise.identity.persistence.FoundationRecord;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "de_ent_grant")
public class EnterpriseGrant extends FoundationRecord {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;
    @Column(name = "subject_id", nullable = false, updatable = false)
    private Long subjectId;
    @Column(name = "policy_kind", nullable = false, updatable = false, length = 24)
    private String policyKind;
    @Column(name = "resource_type", nullable = false, updatable = false, length = 16)
    private String resourceType;
    @Column(name = "resource_scope_kind", nullable = false, updatable = false, length = 32)
    private String resourceScopeKind;
    @Column(name = "resource_id", updatable = false)
    private Long resourceId;
    @Setter(AccessLevel.NONE)
    @Column(name = "resource_key", insertable = false, updatable = false)
    private Long resourceKey;
    @Column(name = "action", nullable = false, updatable = false, length = 8)
    private String action;
    @Column(name = "effect", nullable = false, updatable = false, length = 8)
    private String effect;
    @Column(name = "school_scope_kind", nullable = false, updatable = false, length = 24)
    private String schoolScopeKind;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FoundationStatus status = FoundationStatus.DISABLED;
}
