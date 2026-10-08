# 上游同步与补丁登记

`upstream` 为 GitHub 官方仓库；当前 GitHub Git 连接超时，`gitee` 是 FIT2CLOUD 备用读取源。任何备用源标签必须核对展开后的提交 SHA 与官方标签一致。基线版本和 SHA 记录在 [`baseline.json`](../phase0/baseline.json)。

交付远端以源码树快照为初始提交，未保留上游 Git 祖先关系，因为旧历史中的敏感凭据被 GitHub Push Protection 拦截。不要对远端快照分支直接执行上游 `git merge` 或假设共同祖先存在。完整历史保留在本地 `codex/phase0-upstream-history`；长期保管上游历史应另选受控镜像，但不得将被拦截的秘密推送到项目远端。

上游升级流程：读取新正式版 Release 与安全公告 → 获取标签并核对提交 → 将新旧官方版本树做差异比较 → 比对迁移、API、前端路由和依赖 → 在项目快照分支逐项移植并重构变更 → 跑构建、双租户、权限、嵌入和迁移测试 → 预生产演练 → 发布。不得直接以 `dev-v3` 代替正式标签。

每个改动官方 Core 的补丁在下表新增一行。优先通过公开契约扩展；必须改核心时，把调用方、风险和回归用例记录完整。

| 编号 | 上游文件/入口 | 变更原因 | 扩展替代方案 | 升级冲突风险 | 回归用例 | 负责人 |
| --- | --- | --- | --- | --- | --- | --- |
| ENT-001（W02/T01） | core-backend新增enterprise/bootstrap；core-backend/pom.xml增加enterprise-tests Profile | 企业必需安全服务缺失、歧义或社区替补共存时，在普通Bean初始化前拒绝启动；默认社区模式保持兼容 | 复用公开LoginApi/ResourceAuthApi/RowPermissionsApi/ColumnPermissionsApi；以Spring启动门禁扩展，不修改原查询和登录接口 | 低到中：上游认证/权限API或替补类变化需复核；测试Profile同时明确启用standalone | EnterpriseAssemblyGuardTest；完整应用拒绝启动；见[实现与验证](enterprise-bootstrap.md) | 本项目 |
| ENT-002（W02/T01） | SDK common新增enterprise/context；api-permissions新增AccessContextResolver；core启动门禁追加必需类型及企业测试 | 提供不可变集团/用户/版本事实和显式线程作用域；缺上下文拒绝；仅旧权限API齐备不能代替集团身份能力 | 新增共享基础/认证适配契约，不改原HTTP/查询接口，不注册全局过滤器；W03实现可信解析和接入 | 中：共享契约及后续请求/任务生命周期需独立评审，不能从客户端构造身份；嵌入上限另须明确携带 | AccessContextHolderTest；解析器缺失/名称伪造拒绝；完整包SDK字节核对；见[实现与验证](enterprise-context.md) | 本项目 |
| ENT-003（W02社区兼容） | 前端Handler/MobileHandler、HmacTool及移动登录页面 | 社区包无扩展认证配置接口，初始化404阻塞登录；移动遮罩及密码复杂度预检阻止社区登录 | 使用已有xpackModel明确社区能力，跳过不可用的可选配置；社区凭据交服务端验证，扩展校验保留；不新增伪认证Bean | 中：能力返回值、HMAC初始化及多入口升级需复核；null与false不可混同 | 5项实际HMAC模块合成回归；完整包桌面/移动登录、刷新及企业拒绝启动；见[根因与修复](../planning/w02-login-404.md) | 本项目 |
| ENT-004（W03/T02基础迁移） | InitSqlListener追加组4；新增enterprise/foundation及测试 | 4.1自研四表、显式开关、安全重试和每次启动漂移拒绝 | 沿公开SqlBlock，不引入Flyway，不改已执行版本，不注册JPA实体或HTTP接口 | 中：保留组4独占，复核监听器顺序、版本记录和MySQL元信息；多节点未验证 | FoundationConfigurationTest、FoundationMigrationTest、verify-foundation及完整应用；见[设计与验证](foundation-migration.md) | 本项目 |

`de-xpack` 不参与自研实现与公开提交。涉及 SDK 契约变更时，核对社区替补实现、现有 XPack API 契约及前端调用，但不访问或复制专有实现。

2026-10-08补充ENT-004：FoundationSchema修复字符串默认值、索引字段和CHECK字面量校验；未改4.1 DDL、InitSqlListener或SDK/API。最终必需46项Java、完整包与正式门禁及原因分析见[整改记录](schema-validation-review.md)，后续版本/JPA/就绪设计见[ADR-015](../adr/015-schema-validation-and-startup.md)。

## ENT-005：企业JPA结构所有权

2026-10-08，W03/T02。新增EnterpriseJpaConfiguration、EnterpriseSchemaFilterProvider和EnterpriseMappingGuard，沿Boot/Hibernate公开扩展隔离de_ent_保留结构；不修改社区实体、JpaUpdateNonNullAspect、InitSqlListener、SDK/API或历史4.1 DDL。未知provider组合拒绝，升级时复核SPI、初始化顺序及配置覆盖。真实Boot/MySQL验证和限制见[本轮记录](enterprise-jpa-isolation.md)，新增14项Java进入正式命名门禁。


## ENT-006：正式基础 JPA 映射与条件扫描

2026-10-08，W03/T02。EnterpriseJpaConfiguration 增加 ManagedClassNameFilter；新增 identity.persistence 四实体及公共映射，不改社区扫描包、已有实体、非空合并切面、SDK/API 或 4.1。开关默认／false 排除企业保留包，true 扫描正式实体；自动 DDL 仍由 ENT-005 隔离。升级复核 Boot 对扫描过滤 Bean 的支持、显式 managed-types 与独立工厂的旁路；新 Repository 必须同步验证关闭模式注册和集团限定。新增 12 个真实 Boot/MySQL 回归进入门禁，见[实现记录](foundation-jpa-mapping.md)。

## ENT-007：学校归属、组织领域与事务内核

2026-10-08，W03/T02。新增 enterprise/tenant/domain 纯规则和 tenant/manage 内部 OrganizationTransactionKernel，沿四表 JPA、JpaTransactionManager、IDUtils.snowID()、标准错误码及 SDK AccessContext。未改社区查询／公开 API／非空合并切面，未新增正式 DDL、YAML 或 HTTP。内核不注册默认 Bean，正式资格和同事务审计实现未接入。升级需复核固定锁顺序、tenant 限定、批量 CAS／显式清空、事务回读以及学校作用域链。真实 MySQL／Boot 的 12 项和领域 8 项加入命名门禁，见[单元记录](organization-domain.md)；不访问或复制 XPack 实现。

## ENT-008：历史版本、当前目标及组4历史预检

2026-10-08，W03/T02。InitSqlListener在任何组执行前增加组4连续计划／完整历史只读预检，组4最新版本使用准确命名空间，增加企业rank耗尽拒绝；其他组原行为和SDK Version保持。新增EnterpriseMigrationHistory和FoundationSchemaV41，4.1原DDL指纹不变；FoundationSchema只维护当前目标及通用比较，FoundationSchemaVerifier脱离历史SqlBlock并检查完整保留表清单，FoundationConfiguration沿原条件装配。升级复核版本Repository排序／rank、监听器顺序、MySQL元信息、冻结声明与当前目标同步。没有新增公共API、SDK依赖、正式4.2、默认身份或XPack实现。12个真实／纯规则演进方法进入固定门禁，详见[实现和限制](schema-version-evolution.md)。

## ENT-009：正式4.2审计与组织同事务追加

2026-10-08，W03/T02。新增FoundationSchemaV42、EnterpriseAuditSqlBlock及enterprise.audit下的事件／组织适配器；FoundationSchema当前目标包含第五表，FoundationConfiguration按原foundation开关装配正式4.2。冻结V41、InitSqlListener和SDK公共契约本轮不变。没有XPack实现、默认权限Bean或HTTP。

升级复核组4连续版本、历史成功不重跑、当前五表验证、JPA关闭自动DDL和映射边界、组织内核同事务调用。Spring工厂代理和原生工厂不能直接对象比较，审计要求同一工厂事务资源中的实际EntityManager。16个真实命名回归及固定门禁见[审计记录](organization-audit.md)，不扩大公共接口承诺。
