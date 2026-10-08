# 正式4.3—4.5实际字段字典（2026-10-08）

本节从专用MySQL 13306空合成元库de_phase1_meta的information_schema只读核对生成，对应冻结FoundationSchemaV43/V44/V45与正式SqlBlock；不含用户数据或秘密。它补充实际DDL，替代此前“其余全部待实施”的当前进度，保留4.1/4.2历史与首期未来候选。当前15表164列、50个强制CHECK、56个RESTRICT外键、87个索引，原任务元库所有企业表仍为空；创建身份和接口验收使用独立控制合成元库。下面列出新增10表108列，公共字段也逐表明确。

所有新增表InnoDB、utf8mb4_0900_bin；id使用服务器Snowflake，不自增；UTC时间datetime(6)，created_by/updated_by仅系统初始化可为空，业务写入实际操作者。默认值“无”表示未声明默认，并不表示可空。status默认DISABLED不自动供给权限，会话仅在真实登录事务建立ACTIVE。复合外键保留集团与任职／学校的配对；数据库约束不取代成员有效性和业务鉴权。迁移验证与重试见[实施](../development/w03-control-plane.md)和[验收](../development/w03-acceptance.md)。

### `de_ent_org_member`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `tenant_id` | `bigint` | 否 | 无 | 唯一归属集团ID |
| `org_id` | `bigint` | 否 | 无 | 本集团组织ID |
| `member_id` | `bigint` | 否 | 无 | 本集团成员ID |
| `status` | `varchar(16)` | 否 | `DISABLED` | DISABLED或ACTIVE，初始不授予权限 |

索引：`ix_org_member_created_by(created_by)`；`ix_org_member_lookup(tenant_id,member_id,status,org_id)`；`ix_org_member_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_org_member(tenant_id,org_id,member_id)`唯一；`uk_org_member_tenant_id(tenant_id,id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_org_member_created_by(created_by)→de_ent_user(id)`；`fk_org_member_member_id(tenant_id,member_id)→de_ent_tenant_member(tenant_id,id)`；`fk_org_member_org_id(tenant_id,org_id)→de_ent_org(tenant_id,id)`；`fk_org_member_tenant(tenant_id)→de_ent_tenant(id)`；`fk_org_member_updated_by(updated_by)→de_ent_user(id)`。

强制CHECK：

- `ck_org_member_positive`：`((`id` > 0) and (`version` > 0) and (`tenant_id` > 0))`，YES
- `ck_org_member_status`：`(`status` in (_utf8mb4\'DISABLED\',_utf8mb4\'ACTIVE\'))`，YES

### `de_ent_role`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `tenant_id` | `bigint` | 否 | 无 | 唯一归属集团ID |
| `code` | `varchar(64)` | 否 | 无 | 集团内不可复用角色代码 |
| `name` | `varchar(128)` | 否 | 无 | 角色展示名称 |
| `status` | `varchar(16)` | 否 | `DISABLED` | DISABLED或ACTIVE，初始不授予权限 |

索引：`ix_role_created_by(created_by)`；`ix_role_status(tenant_id,status,id)`；`ix_role_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_role_code(tenant_id,code)`唯一；`uk_role_tenant_id(tenant_id,id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_role_created_by(created_by)→de_ent_user(id)`；`fk_role_tenant(tenant_id)→de_ent_tenant(id)`；`fk_role_updated_by(updated_by)→de_ent_user(id)`。

强制CHECK：

- `ck_role_code`：`((char_length(`code`) > 0) and (char_length(`code`) = char_length(trim(`code`))))`，YES
- `ck_role_positive`：`((`id` > 0) and (`version` > 0) and (`tenant_id` > 0))`，YES
- `ck_role_status`：`(`status` in (_utf8mb4\'DISABLED\',_utf8mb4\'ACTIVE\'))`，YES

### `de_ent_role_assignment`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `tenant_id` | `bigint` | 否 | 无 | 唯一归属集团ID |
| `member_id` | `bigint` | 否 | 无 | 本集团成员ID |
| `role_id` | `bigint` | 否 | 无 | 本集团角色ID |
| `status` | `varchar(16)` | 否 | `DISABLED` | DISABLED或ACTIVE，初始不授予权限 |

索引：`ix_assignment_member(tenant_id,member_id,status)`；`ix_assignment_role(tenant_id,role_id)`；`ix_role_assignment_created_by(created_by)`；`ix_role_assignment_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_assignment(tenant_id,member_id,role_id)`唯一；`uk_role_assignment_tenant_id(tenant_id,id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_assignment_member_id(tenant_id,member_id)→de_ent_tenant_member(tenant_id,id)`；`fk_assignment_role_id(tenant_id,role_id)→de_ent_role(tenant_id,id)`；`fk_role_assignment_created_by(created_by)→de_ent_user(id)`；`fk_role_assignment_tenant(tenant_id)→de_ent_tenant(id)`；`fk_role_assignment_updated_by(updated_by)→de_ent_user(id)`。

强制CHECK：

- `ck_role_assignment_positive`：`((`id` > 0) and (`version` > 0) and (`tenant_id` > 0))`，YES
- `ck_role_assignment_status`：`(`status` in (_utf8mb4\'DISABLED\',_utf8mb4\'ACTIVE\'))`，YES

### `de_ent_assignment_school`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `tenant_id` | `bigint` | 否 | 无 | 唯一归属集团ID |
| `assignment_id` | `bigint` | 否 | 无 | 本集团任职根ID |
| `school_id` | `bigint` | 否 | 无 | 本集团学校组织ID |

索引：`ix_assignment_school_created_by(created_by)`；`ix_assignment_school_reverse(tenant_id,school_id,assignment_id)`；`ix_assignment_school_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_assignment_school(tenant_id,assignment_id,school_id)`唯一；`uk_assignment_school_tenant_id(tenant_id,id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_assignment_school_assignment_id(tenant_id,assignment_id)→de_ent_role_assignment(tenant_id,id)`；`fk_assignment_school_created_by(created_by)→de_ent_user(id)`；`fk_assignment_school_school_id(tenant_id,school_id)→de_ent_org(tenant_id,id)`；`fk_assignment_school_tenant(tenant_id)→de_ent_tenant(id)`；`fk_assignment_school_updated_by(updated_by)→de_ent_user(id)`。

强制CHECK：

- `ck_assignment_school_positive`：`((`id` > 0) and (`version` > 0) and (`tenant_id` > 0))`，YES

### `de_ent_subject`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `tenant_id` | `bigint` | 否 | 无 | 唯一归属集团ID |
| `subject_type` | `varchar(8)` | 否 | 无 | ORG或ROLE或USER |
| `org_id` | `bigint` | 是 | 无 | 仅ORG填写本集团组织ID |
| `role_id` | `bigint` | 是 | 无 | 仅ROLE填写本集团角色ID |
| `member_id` | `bigint` | 是 | 无 | 仅USER填写本集团成员ID |

索引：`ix_subject_created_by(created_by)`；`ix_subject_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_subject_member(tenant_id,member_id)`唯一；`uk_subject_org(tenant_id,org_id)`唯一；`uk_subject_role(tenant_id,role_id)`唯一；`uk_subject_tenant_id(tenant_id,id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_subject_created_by(created_by)→de_ent_user(id)`；`fk_subject_member_id(tenant_id,member_id)→de_ent_tenant_member(tenant_id,id)`；`fk_subject_org_id(tenant_id,org_id)→de_ent_org(tenant_id,id)`；`fk_subject_role_id(tenant_id,role_id)→de_ent_role(tenant_id,id)`；`fk_subject_tenant(tenant_id)→de_ent_tenant(id)`；`fk_subject_updated_by(updated_by)→de_ent_user(id)`。

强制CHECK：

- `ck_subject_branch`：`(((`subject_type` = _utf8mb4\'ORG\') and (`org_id` is not null) and (`role_id` is null) and (`member_id` is null)) or ((`subject_type` = _utf8mb4\'ROLE\') and (`org_id` is null) and (`role_id` is not null) and (`member_id` is null)) or ((`subject_type` = _utf8mb4\'USER\') and (`org_id` is null) and (`role_id` is null) and (`member_id` is not null)))`，YES
- `ck_subject_positive`：`((`id` > 0) and (`version` > 0) and (`tenant_id` > 0))`，YES

### `de_ent_admin_grant`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `tenant_id` | `bigint` | 否 | 无 | 唯一归属集团ID |
| `subject_id` | `bigint` | 否 | 无 | 本集团类型化主体ID |
| `capability` | `varchar(32)` | 否 | 无 | 集团级管理能力代码 |
| `effect` | `varchar(8)` | 否 | 无 | ALLOW或DENY |
| `status` | `varchar(16)` | 否 | `DISABLED` | DISABLED或ACTIVE，初始不授予权限 |

索引：`ix_admin_eval(tenant_id,subject_id,status,capability)`；`ix_admin_grant_created_by(created_by)`；`ix_admin_grant_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_admin_grant(tenant_id,subject_id,capability,effect)`唯一；`uk_admin_grant_tenant_id(tenant_id,id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_admin_grant_created_by(created_by)→de_ent_user(id)`；`fk_admin_grant_subject_id(tenant_id,subject_id)→de_ent_subject(tenant_id,id)`；`fk_admin_grant_tenant(tenant_id)→de_ent_tenant(id)`；`fk_admin_grant_updated_by(updated_by)→de_ent_user(id)`。

强制CHECK：

- `ck_admin_capability`：`(`capability` in (_utf8mb4\'MANAGE_ORGANIZATIONS\',_utf8mb4\'MANAGE_MEMBERS\',_utf8mb4\'MANAGE_ROLES\',_utf8mb4\'MANAGE_AUTHORIZATION\',_utf8mb4\'MANAGE_EMBED_APPS\',_utf8mb4\'MANAGE_DATASOURCE_BINDINGS\',_utf8mb4\'INSTANTIATE_TEMPLATES\'))`，YES
- `ck_admin_effect`：`(`effect` in (_utf8mb4\'ALLOW\',_utf8mb4\'DENY\'))`，YES
- `ck_admin_grant_positive`：`((`id` > 0) and (`version` > 0) and (`tenant_id` > 0))`，YES
- `ck_admin_grant_status`：`(`status` in (_utf8mb4\'DISABLED\',_utf8mb4\'ACTIVE\'))`，YES

### `de_ent_user_credential`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `user_id` | `bigint` | 否 | 无 | 平台用户ID |
| `scheme` | `varchar(32)` | 否 | `PBKDF2_SHA256` | 固定慢哈希算法标识 |
| `encoded_hash` | `varchar(512)` | 否 | 无 | 算法参数盐和摘要，敏感内部字段 |
| `password_changed_at` | `datetime(6)` | 否 | 无 | 服务端最近改密时间UTC |
| `must_reset` | `tinyint(1)` | 否 | `1` | 首次启用必须改密 |
| `failed_attempts` | `int` | 否 | `0` | 连续失败次数 |
| `locked_until` | `datetime(6)` | 是 | 无 | 暂时锁定截止UTC |

索引：`ix_user_credential_created_by(created_by)`；`ix_user_credential_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_credential_user(user_id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_user_credential_created_by(created_by)→de_ent_user(id)`；`fk_user_credential_updated_by(updated_by)→de_ent_user(id)`；`fk_user_credential_user(user_id)→de_ent_user(id)`。

强制CHECK：

- `ck_credential_parameters`：`((`scheme` = _utf8mb4\'PBKDF2_SHA256\') and (`must_reset` in (0,1)) and (`failed_attempts` >= 0))`，YES
- `ck_user_credential_positive`：`((`id` > 0) and (`version` > 0) and (`user_id` > 0))`，YES

### `de_ent_platform_qualification`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `user_id` | `bigint` | 否 | 无 | 具有资格的平台用户ID |
| `qualification` | `varchar(32)` | 否 | 无 | GROUP_READ_ALL或PLATFORM_OPERATE |
| `status` | `varchar(16)` | 否 | `DISABLED` | 初始无资格 |

索引：`ix_platform_qualification_created_by(created_by)`；`ix_platform_qualification_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_platform_qualification(user_id,qualification)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_platform_qualification_created_by(created_by)→de_ent_user(id)`；`fk_platform_qualification_updated_by(updated_by)→de_ent_user(id)`；`fk_platform_qualification_user(user_id)→de_ent_user(id)`。

强制CHECK：

- `ck_platform_qualification_positive`：`((`id` > 0) and (`version` > 0) and (`user_id` > 0))`，YES
- `ck_platform_qualification_values`：`((`qualification` in (_utf8mb4\'GROUP_READ_ALL\',_utf8mb4\'PLATFORM_OPERATE\')) and (`status` in (_utf8mb4\'DISABLED\',_utf8mb4\'ACTIVE\')))`，YES

### `de_ent_login_session`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 会话记录主键，不是认证凭据 |
| `version` | `bigint` | 否 | `1` | 切换与关闭CAS修订 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 建立时间UTC |
| `expires_at` | `datetime(6)` | 否 | 无 | 绝对截止UTC |
| `user_id` | `bigint` | 否 | 无 | 平台用户ID |
| `token_hash` | `binary(32)` | 否 | 无 | 随机会话令牌SHA256摘要 |
| `selected_tenant_id` | `bigint` | 是 | 无 | 当前集团，NULL仅全局入口 |
| `identity_epoch` | `bigint` | 否 | 无 | 登录时身份安全修订 |
| `last_seen_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 最近有效请求UTC |
| `idle_expires_at` | `datetime(6)` | 否 | 无 | 空闲截止不超过绝对截止 |
| `status` | `varchar(16)` | 否 | `ACTIVE` | ACTIVE或CLOSED或REVOKED或EXPIRED |

索引：`ix_login_expiry(status,expires_at,id)`；`ix_login_tenant(selected_tenant_id)`；`ix_login_user(user_id,status)`；`PRIMARY(id)`唯一；`uk_login_token(token_hash)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_login_tenant(selected_tenant_id)→de_ent_tenant(id)`；`fk_login_user(user_id)→de_ent_user(id)`。

强制CHECK：

- `ck_login_positive`：`((`id` > 0) and (`version` > 0) and (`identity_epoch` > 0))`，YES
- `ck_login_status`：`(`status` in (_utf8mb4\'ACTIVE\',_utf8mb4\'CLOSED\',_utf8mb4\'REVOKED\',_utf8mb4\'EXPIRED\'))`，YES
- `ck_login_time`：`((`created_at` < `idle_expires_at`) and (`idle_expires_at` <= `expires_at`) and (`last_seen_at` >= `created_at`) and (`last_seen_at` < `expires_at`))`，YES

### `de_ent_resource`

| 字段 | MySQL类型 | 可空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | `bigint` | 否 | 无 | 记录主键，服务端生成 |
| `version` | `bigint` | 否 | `1` | 乐观锁修订，更新加一 |
| `created_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 创建时间UTC |
| `updated_at` | `datetime(6)` | 否 | `CURRENT_TIMESTAMP(6)` | 修改时间UTC，应用显式更新 |
| `created_by` | `bigint` | 是 | 无 | 创建平台用户，NULL仅受控系统操作 |
| `updated_by` | `bigint` | 是 | 无 | 修改平台用户，NULL仅受控系统操作 |
| `tenant_id` | `bigint` | 否 | 无 | 唯一归属集团ID，必须匹配访问上下文 |
| `resource_type` | `varchar(16)` | 否 | 无 | DATASOURCE或DATASET或QUERY_TABLE或FIELD或CHART或DASHBOARD |
| `resource_kind` | `varchar(16)` | 否 | `STANDARD` | STANDARD或TEMPLATE或SCHOOL_COPY |
| `payload_policy` | `varchar(16)` | 否 | `METADATA_ONLY` | METADATA_ONLY或受控SYNTHETIC_ONLY |
| `sample_provenance_ref` | `varchar(255)` | 是 | 无 | 受控合成样例来源证明，不能由浏览器声明 |
| `parent_resource_id` | `bigint` | 是 | 无 | 本集团依赖父资源，不继承权限 |
| `school_id` | `bigint` | 是 | 无 | 学校副本固定学校，其他首期为空 |
| `status` | `varchar(16)` | 否 | `DISABLED` | 归属及依赖有效方可ACTIVE |

索引：`ix_resource_created_by(created_by)`；`ix_resource_listing(tenant_id,resource_type,status,id)`；`ix_resource_parent(tenant_id,parent_resource_id)`；`ix_resource_school(tenant_id,school_id,resource_type,status)`；`ix_resource_updated_by(updated_by)`；`PRIMARY(id)`唯一；`uk_resource_tenant_id(tenant_id,id)`唯一；`uk_resource_typed(tenant_id,resource_type,id)`唯一。

外键（UPDATE/DELETE RESTRICT）：`fk_resource_created_by(created_by)→de_ent_user(id)`；`fk_resource_parent(tenant_id,parent_resource_id)→de_ent_resource(tenant_id,id)`；`fk_resource_school(tenant_id,school_id)→de_ent_org(tenant_id,id)`；`fk_resource_tenant(tenant_id)→de_ent_tenant(id)`；`fk_resource_updated_by(updated_by)→de_ent_user(id)`。

强制CHECK：

- `ck_resource_kind`：`((`resource_kind` in (_utf8mb4\'STANDARD\',_utf8mb4\'TEMPLATE\',_utf8mb4\'SCHOOL_COPY\')) and ((`resource_type` = _utf8mb4\'DASHBOARD\') or (`resource_kind` = _utf8mb4\'STANDARD\')) and (((`resource_kind` = _utf8mb4\'SCHOOL_COPY\') and (`school_id` is not null)) or ((`resource_kind` <> _utf8mb4\'SCHOOL_COPY\') and (`school_id` is null))))`，YES
- `ck_resource_parent`：`((`parent_resource_id` is null) or (`parent_resource_id` <> `id`))`，YES
- `ck_resource_payload`：`(((`payload_policy` = _utf8mb4\'METADATA_ONLY\') and (`sample_provenance_ref` is null)) or ((`payload_policy` = _utf8mb4\'SYNTHETIC_ONLY\') and (`resource_type` in (_utf8mb4\'CHART\',_utf8mb4\'DASHBOARD\')) and (`sample_provenance_ref` is not null) and (char_length(`sample_provenance_ref`) > 0)))`，YES
- `ck_resource_positive`：`((`id` > 0) and (`version` > 0) and (`tenant_id` > 0))`，YES
- `ck_resource_status`：`(`status` in (_utf8mb4\'DISABLED\',_utf8mb4\'ACTIVE\'))`，YES
- `ck_resource_type`：`(`resource_type` in (_utf8mb4\'DATASOURCE\',_utf8mb4\'DATASET\',_utf8mb4\'QUERY_TABLE\',_utf8mb4\'FIELD\',_utf8mb4\'CHART\',_utf8mb4\'DASHBOARD\'))`，YES

W03只开放集团管理资格及空白DASHBOARD归属。业务策略表、数据源绑定、嵌入票据／会话、任务／文件和outbox等未实施表仍是总体设计；不将管理login_session与未来嵌入Session混用。资格授予／撤销公共接口与角色配置页面待W04，控制面ACTIVE不证明W06业务源已绑定。
