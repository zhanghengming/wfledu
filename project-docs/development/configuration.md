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

## 迁移演进装配边界

2026-10-08，[迁移演进](schema-version-evolution.md)不新增配置开关。FoundationSchemaVerifier使用JdbcTemplate与编译期当前目标，脱离历史SqlBlock；内部测试快照构造器不是可配置生产Bean或默认允许适配器。enterprise.foundation.enabled默认false及原严格开关验证保持；开启仍不等于enterprise.enabled或企业请求就绪。生产4.2未注册，正式资格／审计／身份依赖仍待。

## 正式审计装配边界

2026-10-08，foundation原开关条件注册4.1、正式4.2与当前五表验证器；默认关闭无新增DDL交互。开启仍不等于enterprise.enabled或企业请求就绪。审计适配器不注册默认Bean，必须显式传入实际工厂与可信Trace来源；组织管理资格、可信身份及HTTP就绪继续待实现。没有新配置键、默认身份或临时管理员位。见[正式审计](organization-audit.md)。

## W03管理控制面开关

详见[实施设计](w03-control-plane.md)及[验收环境](w03-acceptance.md)。enterprise.management.enabled默认false；开启要求foundation=true、enterprise.enabled=false，正式4.1—4.5迁移及私有受控初始化完成。bootstrap-file仅显式本机首次使用，权限600、严格JSON，不提供公开初始化；初始化成功后移除此参数，重启不重复授权。allowed-origins可配置精确http／https来源，默认空；不得使用星号或路径／用户信息。

控制模式关闭旧业务、文件、分享、导出及嵌入入口，等待W04／W05／W07安全实现；新随机Bearer会话不兼容社区Token。配置必须同时满足Spring加载位置及ConfigUtils读取的user.home/opt/dataease3.0/config/application.yml，新任务home的缓存、文件和替补路径均指向自身。专用18120仅loopback、Hikari配置目标2条；完整产品实测该账号12条连接，容量按实际总数预算；旧3306／6379／8100及配置不改。ACTIVE集团只表示控制面身份可用，物理业务源需W06验证后开放。


## W04第3步配置增量

本步骤没有新增配置开关、宿主参数或默认用户。enterprise.foundation.enabled=true注册连续4.1—4.7组4迁移；缺省／false仍不加载企业实体及迁移。management测试模式沿用W03专用配置，不开启未完成的完整企业业务取数。现有两套任务配置保持不变，字段与数据边界见[W04存储增量](../technical/w04-storage-dictionary.md)。

授权表默认DISABLED，无自动授权；幂等USER-only，app_id强制NULL，不提前依赖APP表。4.7成功后不能用只认识4.6及以前的二进制启动并删账本“恢复”；兼容回退要求见[迁移规范](migration.md)。单节点合成验收不表示生产部署或宿主联调通过。
