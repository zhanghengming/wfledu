package io.dataease.enterprise.audit.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** Internal append-only mapping. No repository or public CRUD contract. */
@Entity
@Table(name = "de_ent_audit_event")
@Immutable
@Getter
public class EnterpriseAuditEvent {
    @Id @Column(nullable = false) private Long id;
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)") private LocalDateTime createdAt;
    @Column(name = "event_scope", nullable = false, length = 8) private String eventScope;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "actor_kind", nullable = false, length = 8) private String actorKind;
    @Column(name = "actor_user_id") private Long actorUserId;
    @Column(name = "event_type", nullable = false, length = 64) private String eventType;
    @Column(name = "resource_type", length = 32) private String resourceType;
    @Column(name = "resource_id") private Long resourceId;
    @Column(name = "result_code", nullable = false, length = 32) private String resultCode;
    @Column(name = "trace_id", nullable = false, length = 64) private String traceId;
    @Column(name = "access_epoch") private Long accessEpoch;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "json") private String details;

    protected EnterpriseAuditEvent() { }

    public EnterpriseAuditEvent(long id, LocalDateTime createdAt, long tenantId, long actorUserId, String eventType,
                               long organizationId, String traceId, long accessEpoch, long version) {
        this.id = id;
        this.createdAt = createdAt;
        this.eventScope = "TENANT";
        this.tenantId = tenantId;
        this.actorKind = "USER";
        this.actorUserId = actorUserId;
        this.eventType = eventType;
        this.resourceType = "ORGANIZATION";
        this.resourceId = organizationId;
        this.resultCode = "SUCCESS";
        this.traceId = traceId;
        this.accessEpoch = accessEpoch;
        this.details = "{\"version\":" + version + "}";
    }

    @PreRemove
    void rejectRemoval() { throw new IllegalStateException("Security audit events are append-only"); }
}
