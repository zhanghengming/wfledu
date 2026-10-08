package io.dataease.enterprise.management.persistence;

import io.dataease.enterprise.identity.persistence.FoundationRecord;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "de_ent_subject")
public class EnterpriseSubject extends FoundationRecord {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "subject_type", nullable = false, length = 8)
    private String subjectType;

    @Column(name = "org_id", nullable = true)
    private Long orgId;

    @Column(name = "role_id", nullable = true)
    private Long roleId;

    @Column(name = "member_id", nullable = true)
    private Long memberId;
}
