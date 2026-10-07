# 全局配置规范

配置优先级：源码安全默认值 → 环境配置 → 环境变量/Secret。开发、测试、预生产、生产使用不同数据库、Redis 前缀、域名和密钥。仓库只提交示例，不提交实际 `.env`、私钥、Token 或生产连接信息。

| 范围 | 配置项 | 约束 |
| --- | --- | --- |
| 元数据库 | URL、用户、密码、连接池 | 仅从部署环境注入；MySQL 8 首期验证 |
| 缓存/票据 | Redis 地址、认证、数据库、Key 前缀 | 企业模式统一命名空间；不混用默认 JCache 语义 |
| 登录 | OIDC issuer/client、回调 URL、会话 TTL | issuer、audience、签名与回调白名单强校验 |
| 嵌入 | App Secret 加密主密钥、Ticket TTL、Origin | Secret 不到浏览器；Ticket TTL 以分钟计 |
| 数据保护 | 文件根目录或对象存储、加密主密钥 | 租户路径隔离；密钥不与数据放同一备份 |
| 查询与导出 | 超时、并发、最大行数、文件大小 | 平台上限与租户配额同时生效 |
| 运维 | 日志、指标、Trace ID、健康检查 | 日志含租户/主体标识，不含秘密或明文数据 |
| 功能开关 | 企业模式、OIDC、嵌入、行列权限 | 默认关闭未完成功能；企业模式的安全依赖缺失即启动失败 |

本地基础服务变量见 [`deploy/dev/.env.example`](../../deploy/dev/.env.example)；隔离开发用的应用叠加配置见 [`application.local.example.yml`](../../deploy/dev/application.local.example.yml)，尚未执行启动验证。应用配置不在上游 `application.yml` 中直接写死；完成 POC 后新增明确的 `enterprise.*` 配置属性类，并用启动校验检查必填项。上游配置文件中的示例值不得用于生产。

配置变更流程：修改示例和说明 → 校验开发环境启动 → 校验缺失/错误配置的拒绝行为 → 在预生产演练 → 发布。密钥轮换需支持新旧密钥短暂并存，记录审计且不输出密钥值。

## W03基础表迁移开关

enterprise.foundation.enabled默认false，只接受精确true/false。显式true仅启用组4/4.1基础表迁移及每次启动只读结构校验，详见[迁移设计](foundation-migration.md)；不能代替enterprise.enabled、安全装配、集团开通或身份授权。当前任务18100进程显式true，企业模式仍false。新JPA实体尚未注册，后续须解决自动DDL隔离，不能只依赖ddl-auto:update。
