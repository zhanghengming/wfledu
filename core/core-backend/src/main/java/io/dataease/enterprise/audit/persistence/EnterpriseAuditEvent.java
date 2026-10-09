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

    public enum ManagementEvent { LOGIN_SUCCEEDED, LOGIN_DENIED, PASSWORD_CHANGED, SESSION_CLOSED, CONTEXT_SWITCHED,
        PLATFORM_INITIALIZED, USER_CREATED, TENANT_CREATED, MEMBER_CREATED, MEMBER_UPDATED, RESOURCE_REGISTERED, ROLE_CREATED, ROLE_UPDATED, ASSIGNMENT_CREATED, ASSIGNMENT_UPDATED, PERMISSIONS_BATCH, ADMIN_CAPABILITIES_BATCH }

    /** Typed management events: no caller supplied names, credentials or arbitrary details. */
    public static EnterpriseAuditEvent management(long id, LocalDateTime time, Long tenant, Long actor,
                                                   ManagementEvent event, Long resource, Long epoch, long version) {
        if (id <= 0 || time == null || event == null || version <= 0 || (tenant == null) != (epoch == null)
                || tenant != null && (tenant <= 0 || epoch <= 0) || actor != null && actor <= 0 || resource != null && resource <= 0) {
            throw new IllegalArgumentException("Invalid management audit facts");
        }
        var row = new EnterpriseAuditEvent();
        row.id=id; row.createdAt=time; row.eventScope=tenant==null?"GLOBAL":"TENANT"; row.tenantId=tenant;
        row.actorKind=actor==null?"SYSTEM":"USER"; row.actorUserId=actor; row.eventType=event.name();
        row.resourceType=event==ManagementEvent.PERMISSIONS_BATCH || event==ManagementEvent.ADMIN_CAPABILITIES_BATCH?"SUBJECT":event.name().startsWith("ROLE_")?"ROLE":event.name().startsWith("ASSIGNMENT_")?"ASSIGNMENT":event.name().startsWith("MEMBER_")?"MEMBER":event==ManagementEvent.RESOURCE_REGISTERED?"DASHBOARD":event==ManagementEvent.USER_CREATED?"USER":event==ManagementEvent.TENANT_CREATED?"TENANT":"SESSION";
        row.resourceId=resource; row.resultCode=event==ManagementEvent.LOGIN_DENIED?"DENIED":"SUCCESS";
        row.traceId=java.util.UUID.randomUUID().toString(); row.accessEpoch=epoch; row.details="{\"version\":"+version+"}";
        return row;
    }

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
