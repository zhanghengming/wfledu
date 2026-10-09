package io.dataease.enterprise.foundation;

import java.util.ArrayList;
import java.util.List;
import static io.dataease.enterprise.foundation.FoundationSchemaV41.*;

/** Frozen 4.6 policy storage. No permissions or school scope are inferred. */
final class FoundationSchemaV46 {
    private FoundationSchemaV46() { }
    private static FoundationSchema.Table table(String name, String comment, List<FoundationSchema.Column> fields,
                                                List<FoundationSchema.Key> extraKeys,
                                                List<FoundationSchema.ForeignKey> extraRefs,
                                                java.util.Map<String,String> checks) {
        var columns = common();
        columns.add(c("tenant_id","bigint",false,null,"唯一归属集团ID，必须匹配访问上下文"));
        columns.addAll(fields);
        var keys = new ArrayList<>(List.of(k("PRIMARY",true,"id"),k("uk_"+name+"_tenant_id",true,"tenant_id","id"),
                k("ix_"+name+"_created_by",false,"created_by"),k("ix_"+name+"_updated_by",false,"updated_by")));
        keys.addAll(extraKeys);
        var refs = new ArrayList<>(List.of(fk("fk_"+name+"_tenant","de_ent_tenant","tenant_id"),
                fk("fk_"+name+"_created_by","de_ent_user","created_by"),fk("fk_"+name+"_updated_by","de_ent_user","updated_by")));
        refs.addAll(extraRefs);
        return new FoundationSchema.Table("de_ent_"+name,comment,columns,keys,refs,checks);
    }
    private static FoundationSchema.ForeignKey composite(String name, String column, String target) {
        return new FoundationSchema.ForeignKey("fk_"+name,List.of("tenant_id",column),"de_ent_"+target,List.of("tenant_id","id"));
    }
    static final List<FoundationSchema.Table> ADDITIONS = List.of(
            table("grant","普通数据范围与看板操作规则，不包含管理资格",List.of(
                    c("subject_id","bigint",false,null,"本集团类型化组织角色个人主体ID"),
                    c("policy_kind","varchar(24)",false,null,"DATA_ACCESS或RESOURCE_ACTION"),
                    c("resource_type","varchar(16)",false,null,"DATASET或DASHBOARD，核对真实类型"),
                    c("resource_scope_kind","varchar(32)",false,"'EXACT'","EXACT或ALL_DATASETS_IN_TENANT"),
                    c("resource_id","bigint",true,null,"EXACT目标ID，动态全部数据集为空"),
                    new FoundationSchema.Column("resource_key","bigint",true,null,"生成的唯一目标槽，0不代表资源或全局权限",new FoundationSchema.Generation("coalesce(`resource_id`,0)")),
                    c("action","varchar(8)",false,null,"VIEW或EXPORT或DRILL，EDIT仅看板"),
                    c("effect","varchar(8)",false,null,"ALLOW或DENY，允许禁止分别存在"),
                    c("school_scope_kind","varchar(24)",false,null,"EXPLICIT或ALL_ACTIVE_IN_TENANT或ASSIGNMENT或NONE"),
                    c("status","varchar(16)",false,"'DISABLED'","DISABLED或ACTIVE，初始不授予业务权限")),
                    List.of(k("uk_grant_rule",true,"tenant_id","subject_id","policy_kind","resource_type","resource_scope_kind","resource_key","action","effect","school_scope_kind"),
                            k("ix_grant_eval",false,"tenant_id","subject_id","policy_kind","resource_type","action","status"),
                            k("ix_grant_resource",false,"tenant_id","resource_type","resource_id")),
                    List.of(composite("grant_subject","subject_id","subject"),
                            new FoundationSchema.ForeignKey("fk_grant_resource",List.of("tenant_id","resource_type","resource_id"),"de_ent_resource",List.of("tenant_id","resource_type","id"))),
                    checks("ck_grant_positive","(id>0) AND (version>0) AND (tenant_id>0) AND (subject_id>0)",
                            "ck_grant_status","status IN ('DISABLED','ACTIVE')",
                            "ck_grant_effect","effect IN ('ALLOW','DENY')",
                            "ck_grant_policy","((policy_kind='DATA_ACCESS') AND (resource_type='DATASET') AND (action IN ('VIEW','EXPORT','DRILL'))) OR ((policy_kind='RESOURCE_ACTION') AND (resource_type='DASHBOARD') AND (resource_scope_kind='EXACT') AND (action IN ('VIEW','EDIT','EXPORT','DRILL')))",
                            "ck_grant_target","((resource_scope_kind='EXACT') AND (resource_id IS NOT NULL) AND (resource_id>0)) OR ((resource_scope_kind='ALL_DATASETS_IN_TENANT') AND (resource_type='DATASET') AND (resource_id IS NULL))",
                            "ck_grant_scope","(school_scope_kind IN ('EXPLICIT','ALL_ACTIVE_IN_TENANT','ASSIGNMENT','NONE')) AND ((policy_kind<>'DATA_ACCESS') OR (school_scope_kind<>'NONE'))")),
            table("grant_school","普通授权EXPLICIT范围的完整学校关联",List.of(
                    c("grant_id","bigint",false,null,"本集团普通业务规则ID"),
                    c("school_id","bigint",false,null,"本集团可靠学校组织ID，服务核对SCHOOL")),
                    List.of(k("uk_grant_school",true,"tenant_id","grant_id","school_id"),
                            k("ix_grant_school_reverse",false,"tenant_id","school_id","grant_id")),
                    List.of(composite("grant_school_grant","grant_id","grant"),composite("grant_school_school","school_id","org")),
                    checks("ck_grant_school_positive","(id>0) AND (version>0) AND (tenant_id>0) AND (grant_id>0) AND (school_id>0)")));
    static final List<FoundationSchema.Table> TABLES;
    static {
        var tables = new ArrayList<>(FoundationSchemaV45.TABLES);
        tables.addAll(ADDITIONS);TABLES = List.copyOf(tables);
    }
}
