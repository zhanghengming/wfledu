package io.dataease.enterprise.foundation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.dataease.enterprise.foundation.FoundationSchemaV41.c;
import static io.dataease.enterprise.foundation.FoundationSchemaV41.k;

/** Frozen management authority storage. No defaults grant authority. */
final class FoundationSchemaV43 {
    private FoundationSchemaV43() { }

    private static FoundationSchema.ForeignKey tenantRef(String suffix, String column, String target) {
        return new FoundationSchema.ForeignKey("fk_" + suffix + "_" + column, List.of("tenant_id", column), "de_ent_" + target, List.of("tenant_id", "id"));
    }

    private static FoundationSchema.Table t(String suffix, String comment, List<FoundationSchema.Column> fields,
                                            List<FoundationSchema.Key> keys, List<FoundationSchema.ForeignKey> refs, Map<String, String> extra) {
        var columns = FoundationSchemaV41.common();
        columns.add(c("tenant_id", "bigint", false, null, "唯一归属集团ID"));
        columns.addAll(fields);
        var indexes = new ArrayList<>(List.of(k("PRIMARY", true, "id"), k("uk_" + suffix + "_tenant_id", true, "tenant_id", "id"),
                k("ix_" + suffix + "_created_by", false, "created_by"), k("ix_" + suffix + "_updated_by", false, "updated_by")));
        indexes.addAll(keys);
        var foreign = new ArrayList<>(List.of(FoundationSchemaV41.fk("fk_" + suffix + "_tenant", "de_ent_tenant", "tenant_id"),
                FoundationSchemaV41.fk("fk_" + suffix + "_created_by", "de_ent_user", "created_by"),
                FoundationSchemaV41.fk("fk_" + suffix + "_updated_by", "de_ent_user", "updated_by")));
        foreign.addAll(refs);
        var checks = new LinkedHashMap<String, String>();
        checks.put("ck_" + suffix + "_positive", "(id>0) AND (version>0) AND (tenant_id>0)");
        if (fields.stream().anyMatch(f -> f.name().equals("status"))) checks.put("ck_" + suffix + "_status", "status IN ('DISABLED','ACTIVE')");
        checks.putAll(extra);
        return new FoundationSchema.Table("de_ent_" + suffix, comment, columns, indexes, foreign, checks);
    }

    private static FoundationSchema.Column status() { return c("status", "varchar(16)", false, "'DISABLED'", "DISABLED或ACTIVE，初始不授予权限"); }
    private static FoundationSchema.Column id(String name, String comment) { return c(name, "bigint", false, null, comment); }
    private static Map<String, String> check(String name, String expression) { return Map.of(name, expression); }

    static final List<FoundationSchema.Table> ADDITIONS = List.of(
            t("org_member", "成员在集团内的显式组织关系", List.of(id("org_id", "本集团组织ID"), id("member_id", "本集团成员ID"), status()),
                    List.of(k("uk_org_member", true, "tenant_id", "org_id", "member_id"), k("ix_org_member_lookup", false, "tenant_id", "member_id", "status", "org_id")),
                    List.of(tenantRef("org_member", "org_id", "org"), tenantRef("org_member", "member_id", "tenant_member")), Map.of()),
            t("role", "集团内角色定义，不根据名称推导资格", List.of(c("code", "varchar(64)", false, null, "集团内不可复用角色代码"), c("name", "varchar(128)", false, null, "角色展示名称"), status()),
                    List.of(k("uk_role_code", true, "tenant_id", "code"), k("ix_role_status", false, "tenant_id", "status", "id")), List.of(),
                    check("ck_role_code", "(CHAR_LENGTH(code)>0) AND (CHAR_LENGTH(code)=CHAR_LENGTH(TRIM(code)))")),
            t("role_assignment", "成员与角色的成对任职根", List.of(id("member_id", "本集团成员ID"), id("role_id", "本集团角色ID"), status()),
                    List.of(k("uk_assignment", true, "tenant_id", "member_id", "role_id"), k("ix_assignment_member", false, "tenant_id", "member_id", "status"), k("ix_assignment_role", false, "tenant_id", "role_id")),
                    List.of(tenantRef("assignment", "member_id", "tenant_member"), tenantRef("assignment", "role_id", "role")), Map.of()),
            t("assignment_school", "每项任职独立的学校范围", List.of(id("assignment_id", "本集团任职根ID"), id("school_id", "本集团学校组织ID")),
                    List.of(k("uk_assignment_school", true, "tenant_id", "assignment_id", "school_id"), k("ix_assignment_school_reverse", false, "tenant_id", "school_id", "assignment_id")),
                    List.of(tenantRef("assignment_school", "assignment_id", "role_assignment"), tenantRef("assignment_school", "school_id", "org")), Map.of()),
            t("subject", "组织角色个人类型化授权主体", List.of(c("subject_type", "varchar(8)", false, null, "ORG或ROLE或USER"),
                            c("org_id", "bigint", true, null, "仅ORG填写本集团组织ID"), c("role_id", "bigint", true, null, "仅ROLE填写本集团角色ID"), c("member_id", "bigint", true, null, "仅USER填写本集团成员ID")),
                    List.of(k("uk_subject_org", true, "tenant_id", "org_id"), k("uk_subject_role", true, "tenant_id", "role_id"), k("uk_subject_member", true, "tenant_id", "member_id")),
                    List.of(tenantRef("subject", "org_id", "org"), tenantRef("subject", "role_id", "role"), tenantRef("subject", "member_id", "tenant_member")),
                    check("ck_subject_branch", "((subject_type='ORG') AND (org_id IS NOT NULL) AND (role_id IS NULL) AND (member_id IS NULL)) OR "
                            + "((subject_type='ROLE') AND (org_id IS NULL) AND (role_id IS NOT NULL) AND (member_id IS NULL)) OR "
                            + "((subject_type='USER') AND (org_id IS NULL) AND (role_id IS NULL) AND (member_id IS NOT NULL))")),
            t("admin_grant", "独立集团管理资格，不隐式提供业务查看", List.of(id("subject_id", "本集团类型化主体ID"),
                            c("capability", "varchar(32)", false, null, "集团级管理能力代码"), c("effect", "varchar(8)", false, null, "ALLOW或DENY"), status()),
                    List.of(k("uk_admin_grant", true, "tenant_id", "subject_id", "capability", "effect"), k("ix_admin_eval", false, "tenant_id", "subject_id", "status", "capability")),
                    List.of(tenantRef("admin_grant", "subject_id", "subject")),
                    FoundationSchemaV41.checks("ck_admin_effect", "effect IN ('ALLOW','DENY')", "ck_admin_capability", "capability IN ('MANAGE_ORGANIZATIONS','MANAGE_MEMBERS','MANAGE_ROLES','MANAGE_AUTHORIZATION','MANAGE_EMBED_APPS','MANAGE_DATASOURCE_BINDINGS','INSTANTIATE_TEMPLATES')")));

    static final List<FoundationSchema.Table> TABLES;
    static {
        var target = new ArrayList<>(FoundationSchemaV42.TABLES);
        target.addAll(ADDITIONS);
        TABLES = List.copyOf(target);
    }
}
