package io.dataease.enterprise.foundation;

import java.util.ArrayList;
import java.util.List;

import static io.dataease.enterprise.foundation.FoundationSchemaV41.*;

/** Frozen credentials, global qualifications and revocable management sessions. */
final class FoundationSchemaV44 {
    private FoundationSchemaV44() { }

    private static FoundationSchema.Table global(String suffix, String comment, List<FoundationSchema.Column> fields,
                                                  List<FoundationSchema.Key> keys, String... checks) {
        var columns = common();
        columns.addAll(fields);
        var indexes = new ArrayList<>(List.of(k("PRIMARY", true, "id"), k("ix_" + suffix + "_created_by", false, "created_by"),
                k("ix_" + suffix + "_updated_by", false, "updated_by")));
        indexes.addAll(keys);
        var constraints = checks(checks);
        constraints.put("ck_" + suffix + "_positive", "(id>0) AND (version>0) AND (user_id>0)");
        return new FoundationSchema.Table("de_ent_" + suffix, comment, columns, indexes,
                List.of(fk("fk_" + suffix + "_user", "de_ent_user", "user_id"),
                        fk("fk_" + suffix + "_created_by", "de_ent_user", "created_by"),
                        fk("fk_" + suffix + "_updated_by", "de_ent_user", "updated_by")),
                constraints);
    }

    static final List<FoundationSchema.Table> ADDITIONS = List.of(
            global("user_credential", "自研管理慢哈希凭据，禁止输出", List.of(
                            c("user_id", "bigint", false, null, "平台用户ID"),
                            c("scheme", "varchar(32)", false, "'PBKDF2_SHA256'", "固定慢哈希算法标识"),
                            c("encoded_hash", "varchar(512)", false, null, "算法参数盐和摘要，敏感内部字段"),
                            c("password_changed_at", "datetime(6)", false, null, "服务端最近改密时间UTC"),
                            c("must_reset", "tinyint(1)", false, "1", "首次启用必须改密"),
                            c("failed_attempts", "int", false, "0", "连续失败次数"),
                            c("locked_until", "datetime(6)", true, null, "暂时锁定截止UTC")),
                    List.of(k("uk_credential_user", true, "user_id")),
                    "ck_credential_parameters", "(scheme='PBKDF2_SHA256') AND (must_reset IN (0,1)) AND (failed_attempts>=0)"),
            global("platform_qualification", "平台资格与集团管理资格相互独立", List.of(
                            c("user_id", "bigint", false, null, "具有资格的平台用户ID"),
                            c("qualification", "varchar(32)", false, null, "GROUP_READ_ALL或PLATFORM_OPERATE"),
                            c("status", "varchar(16)", false, "'DISABLED'", "初始无资格")),
                    List.of(k("uk_platform_qualification", true, "user_id", "qualification")),
                    "ck_platform_qualification_values", "(qualification IN ('GROUP_READ_ALL','PLATFORM_OPERATE')) AND (status IN ('DISABLED','ACTIVE'))"),
            new FoundationSchema.Table("de_ent_login_session", "正式管理会话，仅保存随机令牌摘要", List.of(
                            c("id", "bigint", false, null, "会话记录主键，不是认证凭据"),
                            c("version", "bigint", false, "1", "切换与关闭CAS修订"),
                            c("created_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "建立时间UTC"),
                            c("expires_at", "datetime(6)", false, null, "绝对截止UTC"),
                            c("user_id", "bigint", false, null, "平台用户ID"),
                            c("token_hash", "binary(32)", false, null, "随机会话令牌SHA256摘要"),
                            c("selected_tenant_id", "bigint", true, null, "当前集团，NULL仅全局入口"),
                            c("identity_epoch", "bigint", false, null, "登录时身份安全修订"),
                            c("last_seen_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "最近有效请求UTC"),
                            c("idle_expires_at", "datetime(6)", false, null, "空闲截止不超过绝对截止"),
                            c("status", "varchar(16)", false, "'ACTIVE'", "ACTIVE或CLOSED或REVOKED或EXPIRED")),
                    List.of(k("PRIMARY", true, "id"), k("uk_login_token", true, "token_hash"),
                            k("ix_login_expiry", false, "status", "expires_at", "id"), k("ix_login_user", false, "user_id", "status"),
                            k("ix_login_tenant", false, "selected_tenant_id")),
                    List.of(fk("fk_login_user", "de_ent_user", "user_id"), fk("fk_login_tenant", "de_ent_tenant", "selected_tenant_id")),
                    checks("ck_login_positive", "(id>0) AND (version>0) AND (identity_epoch>0)",
                            "ck_login_status", "status IN ('ACTIVE','CLOSED','REVOKED','EXPIRED')",
                            "ck_login_time", "(created_at<idle_expires_at) AND (idle_expires_at<=expires_at) AND (last_seen_at>=created_at) AND (last_seen_at<expires_at)")));

    static final List<FoundationSchema.Table> TABLES;
    static {
        var tables = new ArrayList<>(FoundationSchemaV43.TABLES);
        tables.addAll(ADDITIONS);
        TABLES = List.copyOf(tables);
    }
}
