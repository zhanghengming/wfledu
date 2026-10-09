# W04第3步：授权存储字段与迁移增量

本记录对应[W04工作包](../planning/w04-authorization.md)第3步，细化[W04设计](w04-authorization-design.md)与[主字段字典](mysql8-table-dictionary.md)的实施增量。当前状态：4.6／4.7已整包运行，真实MySQL／JPA及运行库45项验收通过；不能据此认定权限命令、求值、页面矩阵或业务取数已实现。最终运行证据见[存储记录](../development/w04-authorization-storage.md)。

## 共用约束

三张表使用InnoDB、utf8mb4_0900_bin；所有字段有中文注释，ID由服务端分配，不自增。表属于企业控制元库，业务数据仍按集团独立数据库隔离。集团内学校业务表共用不等于控制元库每集团一套表。

| 共用字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| id | bigint | 否 | 无 | 记录主键，服务端生成，正数 |
| version | bigint | 否 | 1 | 乐观锁版本，正数；更新加一 |
| created_at | datetime(6) | 否 | CURRENT_TIMESTAMP(6) | UTC创建时间 |
| updated_at | datetime(6) | 否 | CURRENT_TIMESTAMP(6) | UTC修改时间，由应用显式更新 |
| created_by | bigint | 是 | NULL | 全局真实用户ID；NULL仅受控系统操作 |
| updated_by | bigint | 是 | NULL | 全局真实用户ID；NULL仅受控系统操作 |
| tenant_id | bigint | 否 | 无 | 唯一归属集团ID，正数，不由浏览器选择 |

共用主键为PRIMARY(id)，复合唯一键包含(tenant_id,id)，创建者／更新者各有单列索引与de_ent_user外键；tenant_id指向de_ent_tenant。所有外键UPDATE／DELETE均RESTRICT，无级联删除。JPA身份／归属／自然键不允许普通更新替换；不存在“null保存即清空”的假设。跨行主体类型、组织SCHOOL、任职学校有效性由后续命令校验，复合外键只证明同集团和记录存在。

## 4.6：de_ent_grant

普通数据范围与看板操作规则，共17列；不承担管理资格。业务规则与de_ent_admin_grant分别存储，不能互相推导。

| 字段（另含7个共用字段） | MySQL类型 | 可空 | 默认／生成 | 含义 |
| --- | --- | --- | --- | --- |
| subject_id | bigint | 否 | 无 | 本集团类型主体，组织／角色／个人 |
| policy_kind | varchar(24) | 否 | 无 | DATA_ACCESS／RESOURCE_ACTION |
| resource_type | varchar(16) | 否 | 无 | DATASET／DASHBOARD，外键同时核对类型 |
| resource_scope_kind | varchar(32) | 否 | EXACT | EXACT／ALL_DATASETS_IN_TENANT |
| resource_id | bigint | 是 | NULL | 精确资源正数ID；本集团动态全部数据集时为空 |
| resource_key | bigint | 是 | STORED coalesce(`resource_id`,0) | 数据库生成的唯一槽；JPA只读；0不是资源或全局权限 |
| action | varchar(8) | 否 | 无 | VIEW／EXPORT／DRILL；EDIT仅看板 |
| effect | varchar(8) | 否 | 无 | ALLOW／DENY独立记录 |
| school_scope_kind | varchar(24) | 否 | 无 | EXPLICIT／ALL_ACTIVE_IN_TENANT／ASSIGNMENT／NONE |
| status | varchar(16) | 否 | DISABLED | DISABLED／ACTIVE；新记录默认不授予权限 |

附加索引：

| 名称 | 唯一 | 字段顺序／用途 |
| --- | --- | --- |
| uk_grant_rule | 是 | tenant_id,subject_id,policy_kind,resource_type,resource_scope_kind,resource_key,action,effect,school_scope_kind；NULL资源归一化防止重复 |
| ix_grant_eval | 否 | tenant_id,subject_id,policy_kind,resource_type,action,status；策略候选加载 |
| ix_grant_resource | 否 | tenant_id,resource_type,resource_id；归属关联检查 |

附加外键fk_grant_subject：(tenant_id,subject_id)→de_ent_subject(tenant_id,id)；fk_grant_resource：(tenant_id,resource_type,resource_id)→de_ent_resource(tenant_id,resource_type,id)。EXACT分支必须提供正数资源ID；全部数据集分支仅允许DATASET且resource_id必须NULL。不存在跨集团通配资源。

六个CHECK分别检查正数、状态、允许禁止、策略类型与操作组合、资源分支、学校范围。DATA_ACCESS仅DATASET的VIEW／EXPORT／DRILL且范围不能NONE；RESOURCE_ACTION仅精确DASHBOARD的VIEW／EDIT／EXPORT／DRILL。ASSIGNMENT只能绑定角色主体、EXPLICIT集合非空、NONE不关联学校等跨行规则由第4步服务完成，数据库CHECK不能独自证明这些语义。

## 4.6：de_ent_grant_school

普通授权EXPLICIT范围的完整学校关联，共9列。

| 字段（另含7个共用字段） | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| grant_id | bigint | 否 | 无 | 本集团普通业务规则ID |
| school_id | bigint | 否 | 无 | 本集团学校组织ID，服务核对SCHOOL与当前有效状态 |

附加唯一uk_grant_school(tenant_id,grant_id,school_id)；反向索引ix_grant_school_reverse(tenant_id,school_id,grant_id)。附加外键fk_grant_school_grant：(tenant_id,grant_id)→de_ent_grant(tenant_id,id)；fk_grant_school_school：(tenant_id,school_id)→de_ent_org(tenant_id,id)。ck_grant_school_positive检查主键、版本、集团、规则和学校均为正数。完整学校集合替换必须与根规则／修订／审计同事务；该命令归第4步。

## 4.7：de_ent_idempotency

本集团操作者管理命令的幂等安全结果，共18列；当前仅USER，不提前依赖嵌入应用。准备实现，以下约束须真实MySQL验证后才标记通过。

| 字段（另含7个共用字段） | MySQL类型 | 可空 | 默认／生成 | 含义 |
| --- | --- | --- | --- | --- |
| principal_kind | varchar(8) | 否 | 无 | 当前仅USER |
| user_id | bigint | 否 | 无 | 全局真实操作者ID，正数 |
| app_id | bigint | 是 | NULL | 当前CHECK强制NULL；APP留待后续新增迁移 |
| principal_key | bigint | 是 | STORED coalesce(`user_id`,`app_id`) | 唯一操作者槽，只读，不作为身份凭据 |
| operation | varchar(64) | 否 | 无 | PERMISSIONS_BATCH／ADMIN_CAPABILITIES_BATCH |
| idempotency_key | varchar(128) | 否 | 无 | 16—128个ASCII字母、数字、下划线或连字符，区分大小写 |
| request_digest | binary(32) | 否 | 无 | 完整规范请求的SHA256摘要；不保存原请求；JPA防御性复制并校验32字节 |
| response_ref | varchar(255) | 是 | NULL | 安全结果引用，不包含凭据或业务数据 |
| result_metadata | json | 是 | NULL | 仅有界JSON对象，包含安全结果ID／版本／提交epoch；DONE必须非空 |
| state | varchar(16) | 否 | IN_PROGRESS | IN_PROGRESS／DONE／FAILED；正常命令同事务仅提交DONE |
| expires_at | datetime(6) | 否 | 无 | UTC重试截止，必须晚于created_at；24小时业务规则待命令实现 |

附加唯一uk_idempotency(tenant_id,principal_kind,principal_key,operation,idempotency_key)；ix_idempotency_expiry(expires_at,id)；ix_idempotency_user(user_id)。另含共用主键／集团复合唯一／两操作者索引。user_id外键fk_idempotency_user→de_ent_user(id)，当前app_id没有外键。

七个CHECK检查正数、USER分支、操作、键格式／长度、状态、截止、结果JSON。JSON上限按MySQL将JSON转utf8mb4文本后的字节数65536核对，不把字符数当字节数。过期键不因清理自动复用；重试前必须重新验证身份、当前管理资格与摘要，均待第4步命令证明。数据库唯一键解决重复提交竞争，不替代授权、事务或摘要一致性。

## 迁移与验证边界

4.1—4.5冻结文件与DDL不变；4.6、4.7依次执行。每步先核对全部前置表及已存在新表，再创建缺失表，最后严格验证。发现未知保留对象、漂移或较新不支持的历史时拒绝，不ALTER修复、不DROP、不删失败历史、不提供默认授权。DDL部分提交后保留失败记录，重试复用已经符合描述的表。

历史4.6只改变描述类构造方式以消除冷加载循环，DDL指纹必须与修复前JAR一致。每个新版本描述类在独立类加载器中冷加载，不能靠共享JVM热加载顺序。

目标为18表208列、64个CHECK、70个外键、107个索引，当前是待4.7整包实测的目标值。验收覆盖空库、带行升级、重复启动、部分DDL失败重试、结构漂移拒绝、双集团外键、生成槽、JPA只读／CAS／清空／回滚、同键并发竞争。业务接口、实际取数、React嵌入、导出文件与缓存任务仍未由本步骤证明。
