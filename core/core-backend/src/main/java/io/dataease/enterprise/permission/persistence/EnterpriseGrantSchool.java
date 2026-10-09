package io.dataease.enterprise.permission.persistence;

import io.dataease.enterprise.identity.persistence.FoundationRecord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "de_ent_grant_school")
public class EnterpriseGrantSchool extends FoundationRecord {
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;
    @Column(name = "grant_id", nullable = false, updatable = false)
    private Long grantId;
    @Column(name = "school_id", nullable = false, updatable = false)
    private Long schoolId;
}
