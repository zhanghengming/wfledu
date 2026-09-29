# 数据库设计规范

## 命名与键

- 表名和列名使用 `snake_case`；新企业表建议 `de_ent_*` 前缀，与上游表区分。长度兼顾 MySQL 索引和后续方言迁移。
- 业务主键沿用 Long/Snowflake。租户表统一 `tenant_id BIGINT NOT NULL`，创建时由服务端写入；外键关系中需要验证父子资源属于同一租户。
- 唯一约束按 `(tenant_id, business_key)` 建立；高频查询建立以 `tenant_id` 开头的复合索引。索引名称和选择性在真实数据量下验证。
- 审计与事件表按租户、时间和资源建立查询索引；高量数据保留/归档策略单独配置。

## 典型表草案

| 表 | 作用 | 关键约束 |
| --- | --- | --- |
| `de_ent_tenant` | 租户状态、生命周期 | 租户 ID 唯一，停用即拒绝访问 |
| `de_ent_tenant_member` | 平台用户与租户关系 | `(tenant_id, user_id)` 唯一 |
| `de_ent_external_identity` | OIDC 外部主体映射 | `(tenant_id, provider, subject)` 唯一 |
| `de_ent_role` / `de_ent_role_grant` | 租户角色与授权 | 角色和授权均在租户内 |
| `de_ent_embed_app` / `de_ent_embed_ticket` | 嵌入应用与一次性票据 | 密钥密文/哈希，Ticket 有效期和消费时间 |
| `de_ent_audit_event` | 安全审计 | 追加写入，按租户与时间查询 |
| `de_ent_quota` / `de_ent_usage` | 限额和用量 | 计数更新并发安全 |

这些是架构草案，不是已创建的数据库表。现有 `core_*` 和 `per_*` 表的租户化采用迁移分阶段执行，逐表所有权清单见 [租户架构](../architecture/tenant.md)。

## 字段与数据保护

每列注明类型、长度、可空性、默认值、敏感等级和业务含义。时间以 UTC 存储；数据库连接设置 `utf8mb4`。软删除只在需要恢复的业务实体使用，唯一索引要考虑软删除后的重名策略。乐观锁用于并发修改配置和授权；配额扣减使用原子更新。

密码、App Secret 和数据源凭据不存明文；可验证的密钥只存单向哈希，需要解密的凭据采用受控加密主密钥。审计日志不存 Token 和凭据。生产账号只获所需 DDL/DML 权限，迁移与运行权限可分开。

结构变更必须遵循 [迁移规范](migration.md)，不能依赖 JPA `ddl-auto:update` 完成历史回填。生产发布先备份并验证恢复路径。
