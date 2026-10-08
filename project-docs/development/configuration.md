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

## 企业JPA结构所有权配置

2026-10-08实现设计见[企业JPA隔离](enterprise-jpa-isolation.md)。过滤在社区/企业模式均注册，不提供关闭它的业务开关；企业映射要求enterprise.foundation.enabled=true。现有schema_filter_provider或integrator_provider配置冲突明确拒绝，不静默覆盖；其他自定义器不得覆盖该契约。独立EntityManagerFactory不会自动继承本配置，新增持久化单元前需独立验证。不新增YAML默认值或真实身份。


## 正式企业实体的条件扫描

2026-10-08，四表映射进入源码后，enterprise.foundation.enabled 精确 true 同时纳入 io.dataease.enterprise.* 持久化类型；默认／false 由 ManagedClassNameFilter 排除这个保留包，社区实体继续扫描。此包不得放社区持久化类型。显式 managed-types 或独立工厂不自动继承过滤；缺开关时手工注册企业表仍由映射守卫拒绝。没有新增 YAML 默认值、自动身份或管理接口。上文“新 JPA 实体尚未注册”是迁移单元历史，当前以[正式映射记录](foundation-jpa-mapping.md)为准。

## 组织内核的装配边界

2026-10-08，[组织内核](organization-domain.md)不新增配置开关或默认 Bean，构造必须显式给出同一 JPA 工厂／事务管理器、真实管理资格、同事务审计、时钟和正数遍历预算。当前没有生产资格／审计适配器，不开放企业 HTTP；测试适配器只在合成库夹具中装配。现有 enterprise.enabled=false 与 enterprise.foundation.enabled=true 仍仅表示社区兼容运行及基础映射，不是企业业务就绪。
