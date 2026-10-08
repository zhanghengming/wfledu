package io.dataease.enterprise.foundation;

import java.util.ArrayList;
import java.util.List;
import static io.dataease.enterprise.foundation.FoundationSchemaV41.*;

/** Frozen ownership envelope; never infers ownership for existing native resources. */
final class FoundationSchemaV45 {
    private FoundationSchemaV45() { }
    static final List<FoundationSchema.Table> ADDITIONS;
    static final List<FoundationSchema.Table> TABLES;
    static {
        var columns = common();
        columns.addAll(List.of(
                c("tenant_id", "bigint", false, null, "唯一归属集团ID，必须匹配访问上下文"),
                c("resource_type", "varchar(16)", false, null, "DATASOURCE或DATASET或QUERY_TABLE或FIELD或CHART或DASHBOARD"),
                c("resource_kind", "varchar(16)", false, "'STANDARD'", "STANDARD或TEMPLATE或SCHOOL_COPY"),
                c("payload_policy", "varchar(16)", false, "'METADATA_ONLY'", "METADATA_ONLY或受控SYNTHETIC_ONLY"),
                c("sample_provenance_ref", "varchar(255)", true, null, "受控合成样例来源证明，不能由浏览器声明"),
                c("parent_resource_id", "bigint", true, null, "本集团依赖父资源，不继承权限"),
                c("school_id", "bigint", true, null, "学校副本固定学校，其他首期为空"),
                c("status", "varchar(16)", false, "'DISABLED'", "归属及依赖有效方可ACTIVE")));
        ADDITIONS = List.of(new FoundationSchema.Table("de_ent_resource", "真实资源的权威安全归属，不保存业务结果", columns,
                List.of(k("PRIMARY", true, "id"), k("uk_resource_tenant_id", true, "tenant_id", "id"),
                        k("uk_resource_typed", true, "tenant_id", "resource_type", "id"),
                        k("ix_resource_listing", false, "tenant_id", "resource_type", "status", "id"),
                        k("ix_resource_school", false, "tenant_id", "school_id", "resource_type", "status"),
                        k("ix_resource_parent", false, "tenant_id", "parent_resource_id"),
                        k("ix_resource_created_by", false, "created_by"), k("ix_resource_updated_by", false, "updated_by")),
                List.of(fk("fk_resource_tenant", "de_ent_tenant", "tenant_id"),
                        fk("fk_resource_created_by", "de_ent_user", "created_by"), fk("fk_resource_updated_by", "de_ent_user", "updated_by"),
                        new FoundationSchema.ForeignKey("fk_resource_parent", List.of("tenant_id", "parent_resource_id"), "de_ent_resource", List.of("tenant_id", "id")),
                        new FoundationSchema.ForeignKey("fk_resource_school", List.of("tenant_id", "school_id"), "de_ent_org", List.of("tenant_id", "id"))),
                checks("ck_resource_positive", "(id>0) AND (version>0) AND (tenant_id>0)",
                        "ck_resource_status", "status IN ('DISABLED','ACTIVE')",
                        "ck_resource_type", "resource_type IN ('DATASOURCE','DATASET','QUERY_TABLE','FIELD','CHART','DASHBOARD')",
                        "ck_resource_kind", "(resource_kind IN ('STANDARD','TEMPLATE','SCHOOL_COPY')) AND ((resource_type='DASHBOARD') OR (resource_kind='STANDARD')) AND (((resource_kind='SCHOOL_COPY') AND (school_id IS NOT NULL)) OR ((resource_kind<>'SCHOOL_COPY') AND (school_id IS NULL)))",
                        "ck_resource_parent", "(parent_resource_id IS NULL) OR (parent_resource_id<>id)",
                        "ck_resource_payload", "((payload_policy='METADATA_ONLY') AND (sample_provenance_ref IS NULL)) OR ((payload_policy='SYNTHETIC_ONLY') AND (resource_type IN ('CHART','DASHBOARD')) AND (sample_provenance_ref IS NOT NULL) AND (CHAR_LENGTH(sample_provenance_ref)>0))")));
        var tables = new ArrayList<>(FoundationSchemaV44.TABLES);
        tables.addAll(ADDITIONS);
        TABLES = List.copyOf(tables);
    }
}
