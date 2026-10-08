package io.dataease.enterprise.foundation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Frozen 4.2 audit addition; never alter the preceding 4.1 definition. */
final class FoundationSchemaV42 {
    private FoundationSchemaV42() { }

    private static FoundationSchema.Column c(String name, String type, boolean nullable, String def, String comment) {
        return new FoundationSchema.Column(name, type, nullable, def, comment);
    }

    private static LinkedHashMap<String, String> checks() {
        var result = new LinkedHashMap<String, String>();
        result.put("ck_audit_id", "id>0");
        result.put("ck_audit_scope", "((event_scope='GLOBAL') AND (tenant_id IS NULL) AND (access_epoch IS NULL)) OR "
                + "((event_scope='TENANT') AND (tenant_id IS NOT NULL) AND (tenant_id>0) AND (access_epoch IS NOT NULL) AND (access_epoch>0))");
        result.put("ck_audit_actor", "((actor_kind='SYSTEM') AND (actor_user_id IS NULL)) OR "
                + "((actor_kind='USER') AND (actor_user_id IS NOT NULL) AND (actor_user_id>0))");
        result.put("ck_audit_resource", "(resource_id IS NULL) OR (resource_id>0)");
        result.put("ck_audit_codes", "(CHAR_LENGTH(event_type)>0) AND (CHAR_LENGTH(event_type)=CHAR_LENGTH(TRIM(event_type))) "
                + "AND (CHAR_LENGTH(result_code)>0) AND (CHAR_LENGTH(result_code)=CHAR_LENGTH(TRIM(result_code))) "
                + "AND (CHAR_LENGTH(trace_id)>0) AND (CHAR_LENGTH(trace_id)=CHAR_LENGTH(TRIM(trace_id)))");
        result.put("ck_audit_details", "(JSON_TYPE(details)='OBJECT') AND (LENGTH(details)<=4096)");
        return result;
    }

    static final FoundationSchema.Table AUDIT = new FoundationSchema.Table("de_ent_audit_event", "安全审计事件，只追加且载荷最小化",
            List.of(c("id", "bigint", false, null, "追加记录主键"),
                    c("created_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "记录写入时间UTC"),
                    c("event_scope", "varchar(8)", false, null, "GLOBAL或TENANT事件范围"),
                    c("tenant_id", "bigint", true, null, "TENANT必须填写，GLOBAL为空"),
                    c("actor_kind", "varchar(8)", false, null, "USER或SYSTEM操作者"),
                    c("actor_user_id", "bigint", true, null, "USER必须填写，SYSTEM为空"),
                    c("event_type", "varchar(64)", false, null, "稳定事件代码"),
                    c("resource_type", "varchar(32)", true, null, "审计对象类型，不作为授权输入"),
                    c("resource_id", "bigint", true, null, "可定位的对象ID"),
                    c("result_code", "varchar(32)", false, null, "SUCCESS或DENIED等稳定结果代码"),
                    c("trace_id", "varchar(64)", false, null, "关联请求或任务编号，不含凭据"),
                    c("access_epoch", "bigint", true, null, "集团修订，GLOBAL为空"),
                    c("details", "json", false, null, "最小字段或版本信息，不含秘密或业务数据")),
            List.of(new FoundationSchema.Key("PRIMARY", true, List.of("id")),
                    new FoundationSchema.Key("ix_audit_tenant_time", false, List.of("tenant_id", "created_at", "id")),
                    new FoundationSchema.Key("ix_audit_actor", false, List.of("actor_user_id", "created_at", "id")),
                    new FoundationSchema.Key("ix_audit_trace", false, List.of("trace_id"))),
            List.of(new FoundationSchema.ForeignKey("fk_audit_tenant", List.of("tenant_id"), "de_ent_tenant", List.of("id")),
                    new FoundationSchema.ForeignKey("fk_audit_actor", List.of("actor_user_id"), "de_ent_user", List.of("id"))), checks());

    static final List<FoundationSchema.Table> TABLES = tables();
    private static List<FoundationSchema.Table> tables() {
        var result = new ArrayList<>(FoundationSchemaV41.TABLES);
        result.add(AUDIT);
        return List.copyOf(result);
    }
}
