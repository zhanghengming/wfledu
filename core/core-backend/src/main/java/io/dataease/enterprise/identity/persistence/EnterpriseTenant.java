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
@Table(name = "de_ent_tenant")
public class EnterpriseTenant extends FoundationRecord {
    @Column(name = "code", length = 64, nullable = false, updatable = false)
    private String code;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private FoundationStatus status = FoundationStatus.DISABLED;

    @Column(name = "access_epoch", nullable = false)
    private Long accessEpoch = 1L;
}
