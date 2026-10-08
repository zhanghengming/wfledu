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
@Table(name = "de_ent_org")
public class EnterpriseOrganization extends FoundationRecord {
    public enum Kind {
        SCHOOL,
        DEPARTMENT
    }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "parent_id")
    private Long parentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "school_code", length = 64, updatable = false)
    private String schoolCode;

    @Column(name = "school_id")
    private Long schoolId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private FoundationStatus status = FoundationStatus.DISABLED;
}
