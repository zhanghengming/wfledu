package io.dataease.enterprise.management.persistence;

import io.dataease.enterprise.identity.persistence.FoundationRecord;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name = "de_ent_resource")
public class EnterpriseResource extends FoundationRecord {
    @Column(name = "tenant_id", nullable = false, updatable = false) private Long tenantId;
    @Column(name = "resource_type", nullable = false, length = 16, updatable = false) private String resourceType;
    @Column(name = "resource_kind", nullable = false, length = 16, updatable = false) private String resourceKind = "STANDARD";
    @Column(name = "payload_policy", nullable = false, length = 16, updatable = false) private String payloadPolicy = "METADATA_ONLY";
    @Column(name = "sample_provenance_ref", length = 255, updatable = false) private String sampleProvenanceRef;
    @Column(name = "parent_resource_id", updatable = false) private Long parentResourceId;
    @Column(name = "school_id", updatable = false) private Long schoolId;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 16) private FoundationStatus status = FoundationStatus.DISABLED;
}
