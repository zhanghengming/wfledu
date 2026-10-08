package io.dataease.enterprise.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="de_ent_platform_qualification") @Getter @Setter
public class EnterprisePlatformQualification extends FoundationRecord {
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(nullable=false,length=32,updatable=false) private String qualification;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private FoundationStatus status = FoundationStatus.DISABLED;
}
