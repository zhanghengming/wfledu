package io.dataease.enterprise.management.persistence;

import io.dataease.enterprise.identity.persistence.FoundationRecord;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "de_ent_role_assignment")
public class EnterpriseRoleAssignment extends FoundationRecord {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)

    @Column(name = "status", nullable = false, length = 16)
    private FoundationStatus status = FoundationStatus.DISABLED;
}
