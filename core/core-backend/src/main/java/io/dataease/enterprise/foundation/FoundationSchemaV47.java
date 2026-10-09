package io.dataease.enterprise.foundation;

import java.util.ArrayList;
import java.util.List;
import static io.dataease.enterprise.foundation.FoundationSchemaV41.*;

/** Frozen 4.7 USER-only command result storage, without an embedded-app dependency. */
final class FoundationSchemaV47 {
    private FoundationSchemaV47() { }
    static final List<FoundationSchema.Table> ADDITIONS;
    static final List<FoundationSchema.Table> TABLES;
    static {
        var columns=common();
        columns.addAll(List.of(
                c("tenant_id","bigint",false,null,"唯一归属集团ID，必须匹配访问上下文"),
                c("principal_kind","varchar(8)",false,null,"当前仅USER，APP由后续迁移扩展"),
                c("user_id","bigint",false,null,"真实操作者全局用户ID，不能由浏览器选择"),
                c("app_id","bigint",true,null,"当前强制为空，尚无嵌入应用依赖"),
                new FoundationSchema.Column("principal_key","bigint",true,null,"生成的操作者唯一槽，只读且不作为身份凭据",new FoundationSchema.Generation("coalesce(`user_id`,`app_id`)")),
                c("operation","varchar(64)",false,null,"PERMISSIONS_BATCH或ADMIN_CAPABILITIES_BATCH"),
                c("idempotency_key","varchar(128)",false,null,"16至128字符随机请求键，非认证凭据"),
                c("request_digest","binary(32)",false,null,"规范化完整请求的SHA256摘要，不保存原始请求"),
                c("response_ref","varchar(255)",true,null,"安全结果引用，不包含凭据或业务数据"),
                c("result_metadata","json",true,null,"有界结果ID版本提交epoch对象，DONE必须非空"),
                c("state","varchar(16)",false,"'IN_PROGRESS'","IN_PROGRESS或DONE或FAILED，正常命令只提交DONE"),
                c("expires_at","datetime(6)",false,null,"服务端产生的UTC重试截止，晚于创建时间")));
        ADDITIONS=List.of(new FoundationSchema.Table("de_ent_idempotency","本集团操作者管理命令的幂等安全结果，不缓存凭据",columns,
                List.of(k("PRIMARY",true,"id"),k("uk_idempotency_tenant_id",true,"tenant_id","id"),
                        k("ix_idempotency_created_by",false,"created_by"),k("ix_idempotency_updated_by",false,"updated_by"),
                        k("uk_idempotency",true,"tenant_id","principal_kind","principal_key","operation","idempotency_key"),
                        k("ix_idempotency_expiry",false,"expires_at","id"),k("ix_idempotency_user",false,"user_id")),
                List.of(fk("fk_idempotency_tenant","de_ent_tenant","tenant_id"),
                        fk("fk_idempotency_created_by","de_ent_user","created_by"),fk("fk_idempotency_updated_by","de_ent_user","updated_by"),
                        fk("fk_idempotency_user","de_ent_user","user_id")),
                checks("ck_idempotency_positive","(id>0) AND (version>0) AND (tenant_id>0)",
                        "ck_idempotency_principal","(principal_kind='USER') AND (user_id>0) AND (app_id IS NULL)",
                        "ck_idempotency_operation","operation IN ('PERMISSIONS_BATCH','ADMIN_CAPABILITIES_BATCH')",
                        "ck_idempotency_key","(CHAR_LENGTH(idempotency_key) BETWEEN 16 AND 128) AND (REGEXP_LIKE(idempotency_key,'[^A-Za-z0-9_-]','c')=0)",
                        "ck_idempotency_state","state IN ('IN_PROGRESS','DONE','FAILED')",
                        "ck_idempotency_expiry","expires_at>created_at",
                        "ck_idempotency_result","((result_metadata IS NULL) AND (state<>'DONE')) OR ((result_metadata IS NOT NULL) AND (JSON_TYPE(result_metadata)='OBJECT') AND (LENGTH(result_metadata)<=65536))")));
        var tables=new ArrayList<>(FoundationSchemaV46.TABLES);tables.addAll(ADDITIONS);TABLES=List.copyOf(tables);
    }
}
