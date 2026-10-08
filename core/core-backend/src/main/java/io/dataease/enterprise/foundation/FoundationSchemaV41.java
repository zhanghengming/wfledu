package io.dataease.enterprise.foundation;

import io.dataease.enterprise.foundation.FoundationSchema.Column;
import io.dataease.enterprise.foundation.FoundationSchema.ForeignKey;
import io.dataease.enterprise.foundation.FoundationSchema.Key;
import io.dataease.enterprise.foundation.FoundationSchema.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen 4.1 definition. Append a new version; never change these historical fields or builders. */
public final class FoundationSchemaV41 {
    private FoundationSchemaV41() { }

    static Column c(String name, String type, boolean nullable, String def, String comment) {
        return new Column(name, type, nullable, def, comment);
    }

    static Key k(String name, boolean unique, String... columns) {
        return new Key(name, unique, List.of(columns));
    }

    static ForeignKey fk(String name, String target, String column) {
        return new ForeignKey(name, List.of(column), target, List.of("id"));
    }

    static List<Column> common() {
        return new ArrayList<>(List.of(
                c("id", "bigint", false, null, "记录主键，服务端生成"),
                c("version", "bigint", false, "1", "乐观锁修订，更新加一"),
                c("created_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "创建时间UTC"),
                c("updated_at", "datetime(6)", false, "CURRENT_TIMESTAMP(6)", "修改时间UTC，应用显式更新"),
                c("created_by", "bigint", true, null, "创建平台用户，NULL仅受控系统操作"),
                c("updated_by", "bigint", true, null, "修改平台用户，NULL仅受控系统操作")));
    }

    static Table table(String suffix, String comment, List<Column> fields, List<Key> keys,
                       List<ForeignKey> refs, Map<String, String> specificChecks) {
        String name = "de_ent_" + suffix;
        List<Column> columns = common();
        columns.addAll(fields);
        List<Key> indices = new ArrayList<>(List.of(k("PRIMARY", true, "id")));
        indices.addAll(keys);
        List<ForeignKey> foreignKeys = new ArrayList<>(List.of(
                fk("fk_" + suffix + "_created_by", "de_ent_user", "created_by"),
                fk("fk_" + suffix + "_updated_by", "de_ent_user", "updated_by")));
        // Explicit support indices keep SHOW INDEX independent of engine-generated names.
        indices.add(k("ix_" + suffix + "_created_by", false, "created_by"));
        indices.add(k("ix_" + suffix + "_updated_by", false, "updated_by"));
        foreignKeys.addAll(refs);
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("ck_" + suffix + "_positive", "(`id` > 0) AND (`version` > 0)");
        checks.put("ck_" + suffix + "_status", "`status` IN ('DISABLED','ACTIVE')");
        checks.putAll(specificChecks);
        return new Table(name, comment, columns, indices, foreignKeys, checks);
    }

    static Map<String, String> checks(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    static final List<Table> TABLES = List.of(
            table("user", "平台全局账号", List.of(
                    c("username", "varchar(128)", false, null, "精确匹配的登录账号，保留占用"),
                    c("display_name", "varchar(128)", false, null, "用户展示名"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE"),
                    c("identity_epoch", "bigint", false, "1", "全局身份安全修订号"),
                    c("legacy_uid", "bigint", true, null, "经审查确认的旧账号编号")),
                    List.of(k("uk_user_name", true, "username"), k("uk_user_legacy", true, "legacy_uid"),
                            k("ix_user_status", false, "status", "id")), List.of(),
                    checks("ck_user_epoch", "`identity_epoch` > 0", "ck_user_legacy", "(`legacy_uid` IS NULL) OR (`legacy_uid` > 0)",
                            "ck_user_username", "(CHAR_LENGTH(`username`) > 0) AND (`username` = TRIM(`username`))")),
            table("tenant", "集团租户主表", List.of(
                    c("code", "varchar(64)", false, null, "不可复用的集团代码"),
                    c("name", "varchar(128)", false, null, "集团展示名称"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE，完成开通才能启用"),
                    c("access_epoch", "bigint", false, "1", "集团安全修订号")),
                    List.of(k("uk_tenant_code", true, "code"), k("ix_tenant_status", false, "status", "id")), List.of(),
                    checks("ck_tenant_epoch", "`access_epoch` > 0", "ck_tenant_code", "(CHAR_LENGTH(`code`) > 0) AND (`code` = TRIM(`code`))")),
            table("tenant_member", "全局账号在集团的成员身份", List.of(
                    c("tenant_id", "bigint", false, null, "唯一归属集团ID"),
                    c("user_id", "bigint", false, null, "全局账号ID"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE")),
                    List.of(k("uk_member_tenant_id", true, "tenant_id", "id"), k("uk_member_user", true, "tenant_id", "user_id"),
                            k("ix_member_user", false, "user_id", "status", "tenant_id")),
                    List.of(fk("fk_member_tenant", "de_ent_tenant", "tenant_id"), fk("fk_member_user", "de_ent_user", "user_id")), checks()),
            table("org", "学校机构组织树及权威学校编号", List.of(
                    c("tenant_id", "bigint", false, null, "唯一归属集团ID"),
                    c("parent_id", "bigint", true, null, "集团内父组织，根节点NULL"),
                    c("kind", "varchar(16)", false, null, "SCHOOL或DEPARTMENT"),
                    c("name", "varchar(128)", false, null, "组织名称"),
                    c("school_code", "varchar(64)", true, null, "仅学校填写，全局唯一业务学校编号"),
                    c("school_id", "bigint", true, null, "机构所属学校，集团直属机构NULL"),
                    c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE")),
                    List.of(k("uk_org_tenant_id", true, "tenant_id", "id"), k("uk_org_school_code", true, "school_code"),
                            k("ix_org_parent", false, "tenant_id", "parent_id", "status"), k("ix_org_school", false, "tenant_id", "school_id", "status")),
                    List.of(fk("fk_org_tenant", "de_ent_tenant", "tenant_id"),
                            new ForeignKey("fk_org_parent", List.of("tenant_id", "parent_id"), "de_ent_org", List.of("tenant_id", "id")),
                            new ForeignKey("fk_org_school", List.of("tenant_id", "school_id"), "de_ent_org", List.of("tenant_id", "id"))),
                    checks("ck_org_kind", "((`kind` = 'SCHOOL') AND (`school_code` IS NOT NULL) AND (`school_id` IS NULL)) OR ((`kind` = 'DEPARTMENT') AND (`school_code` IS NULL))",
                            "ck_org_parent", "(`parent_id` IS NULL) OR (`parent_id` <> `id`)",
                            "ck_org_school", "(`school_id` IS NULL) OR (`school_id` <> `id`)",
                            "ck_org_school_code", "(`school_code` IS NULL) OR ((CHAR_LENGTH(`school_code`) > 0) AND (`school_code` = TRIM(`school_code`)))")));

}
