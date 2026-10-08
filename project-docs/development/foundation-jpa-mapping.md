# W03：四张基础表的正式 JPA 映射

日期：2026-10-08。状态：四表正式映射、真实存储回归及本轮完整包门禁已通过；最终提交后回执与 Git 状态另行绑定留证。业务验收未整体通过。对应 W03/T02、R01/R03/R07/R12、ADR-015。前置为[企业 JPA 隔离](enterprise-jpa-isolation.md)，字段唯一出处仍为[MySQL 字典](../technical/mysql8-table-dictionary.md)。

## 本轮范围与前检

本轮将已迁移的用户、集团、集团成员和组织四表映射为正式 JPA 实体，验证条件扫描、实际读写、乐观版本与元数据事务。学校归属查询、组织树命令、管理资格、epoch 自动递增、审计服务、可信身份及企业 HTTP 就绪属于接续单元；不新增公共 CRUD 接口。

已读取根 AGENTS、远程开发规范、standards、build-and-run、testing、W02 环境、W03、导航/路线/追溯、ADR-015、MySQL 设计/字典、data-model、DDD 聚合/一致性及数据库/迁移规范；读取 JpaUpdateNonNullAspect、FoundationSchema、JPA 隔离配置与真实测试夹具。没有局部 AGENTS。远程开发主目录为 `/home/data_dev_zhm/dataease-phase1-test/w02-security/source`，任务分支 `codex/phase1-security-baseline`，基准 `af381aaa6fc4815b38de5d588e8db83fd29b4873`，编码前暂存区为空，两端原有修改保留。前检专用 PID 1337941 存活，JAR SHA256 `7a1b4c01fe9cc8700d034feb50d24265543b945721b0a7de93fa9414db69dd9e`；此值是变更前快照。

继续使用已经授权的 MySQL 13306、合成 W03 库、Redis 16379、18100 专用应用及 18010 转发；保留旧数据库、目录、服务和所有合成测试现场。沿既有授权只重启本轮专用进程、提交推送任务分支，不合入开发分支、不开放企业模式。4.1 DDL 冻结，没有新增正式表设计或迁移版本；测试在新合成库复用 4.1 建表，任务元库结构不变。

## 架构与设计复查

直接在默认扫描包添加实体会导致 foundation=false 的社区启动也出现企业映射，触发上一单元的关闭拒绝守卫。使用当前 Spring Boot 已消费的 `ManagedClassNameFilter` 扩展点：精确开关 true 才纳入 `io.dataease.enterprise.*`，默认/false 排除这个保留包，社区类型仍正常扫描。不替换自动 managed-types Bean，不改主启动类或社区扫描包；显式自定义持久化单元仍需独立审核，映射守卫继续拒绝关闭时手工注册企业表。

源码依据为任务已有 Boot 3.5.15 的 JpaBaseConfiguration$PersistenceManagedTypesConfiguration 字节码：扫描器接收 ManagedClassNameFilter；接口语义见[Spring Framework 官方文档](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/orm/jpa/persistenceunit/ManagedClassNameFilter.html)。线上文档可能滚动更新，版本以任务依赖为准。

持久化实体归属 identity.persistence，采用公共 MappedSuperclass 的应用分配 Long 主键、@Version 与 UTC DATETIME(6) 审计字段；四表只保存标量引用，不做跨聚合级联或推导授权。自然键、集团归属及成员 userId 的 JPA 普通更新不可变；默认 DISABLED，version/epoch=1，初始化时间由受控命令明确提供，不用实体构造时间替代审计时间。映射不负责创建索引/外键/默认值，真实结构由 4.1 与严格验证器管理。

实体不是领域服务或外部 DTO。新增受控命令明确使用 EntityManager.persist，不能让已赋值的 version=1 被 Spring Data 误判为既有记录。@Version 只覆盖受管更新/正确 merge；批量 JPQL 必须手工含版本条件、递增版本并清理上下文。不能通过 JpaRepository.save 保存浏览器传入实体：现有非空合并切面还可能覆盖版本，且继承字段的主键发现不是可靠的企业命令边界。后续使用租户限定查询及显式 CAS 更新，资格校验/epoch/审计同事务；本轮不改写社区切面。

## 预定验证与完成门槛

新增真实 Boot 自动扫描与 MySQL 集成回归，不用手工注册 managed-types 代替扫描测试。覆盖默认/关闭/开启、四实体精确字段与只读结构保持、尚未迁移时不自动建表、完整字段往返（微秒/中文/大 Long/前导零/NULL）、版本冲突、显式清空、多表回滚、双向跨集团外键、停用学校唯一编号保留、相同用户双集团成员及重复成员拒绝。查询授权与组织树无环不由这些存储测试证明。

按[测试固定清单](testing.md#后续验证固定执行清单)，远程真实 Java XML 和必需方法名称加入正式回执工具；重新整体构建 SDK、前端、后端，运行新完整 JAR，完成桌面/移动 8 项与 3 类故障控制，提交后复验再推送。检查本轮差异、Markdown 链接、凭据与生成物；记录实际结果、产物和限制。独立评审继续由用户/团队人工完成，开发者复查不代替独立通过。

## 已执行验证与评审补强

首次定向执行在测试编译阶段发现 List<Class<?>> 泛型捕获错误，未运行新用例；修正测试代码后，全套 72 项实际执行，失败／错误／跳过均为 0，日志 w03-mapping-tests-01.log／02.log。20 项回执自检通过。SDK、前端 distributed、后端 standalone 整体构建均成功；日志 w03-mapping-sdk-build.log／frontend-build.log／backend-build.log。生产 JAR 包含六个正式映射类型，不含 jpafixture 或测试类。

第一轮完整应用门禁 cf15c80a-92ff-4973-9756-7ffc413c5290 通过 72 Java、20 回执、8 浏览器与 3 类故障控制；该轮是评审补强前的证据。新包实际启动 PID 1346514、SHA d1fa01788775097b53d66355ff52f22dbfaf50d392a31a4de42a7ee9c53a58f0，也是中间快照，最终值以收尾回执为准。

收尾开发者复查发现拒绝用例仅断言 PersistenceException 仍可能误收非目标异常，首次收紧到外键名称后实测 72 项中 1 项失败；原因是本测试账号收到 MySQL 1216／SQLState 23000，文本没有约束名，不能断言详细错误字符串。MySQL 的错误展示与父表元数据权限相关，见[官方外键文档](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)及[8.0 错误字典](https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html)。最终保留最小权限：只接受外键专用 1216／1452 加 SQLState 23000，先证明源集团／目标学校存在、主键未占用，夹具只有一个跨集团引用偏差；学校编号重复仍必须命中 uk_org_school_code，成员重复命中 uk_member_user。优化重新执行测试并生成完整 JAR，补强前旧回执不能代表最终通过。这是测试精度及权限环境兼容修正，没有变更生产实体、数据库结构或账号授权；失败现场保留在 w03-mapping-reviewed-backend-build.log。

## 架构优化复查与后续边界

- 已落实：启动扫描与正式迁移／DDL 所有权分开；保留包条件扫描沿 Boot 扩展，四表映射只承载存储，拒绝路径进入命名门禁。
- 已落实：不用宽泛异常代表数据库拒绝；真实 SQL 必须命中本场景的专用错误类型，配合只包含单一偏差的夹具与事务后独立重读；不依赖低权限下可能缺失的错误详情。默认与关闭模式从真实扫描入口验证，防止手工 managed-types 夹具掩盖自动扫描问题。
- 进入下一单元：组织树命令先锁定同集团安全修订记录，校验整条父链，防止并发修改分别合法但提交后成环；版本与集团 epoch、审计同事务，失败均不提交。资格／审计／CAS 服务未实现，本轮不作业务可用承诺。
- 持久化边界：updatable=false 只约束普通 ORM SQL，不能代替权限或输入校验；被 setter 修改后的内存对象仍可携带非权威值。命令只接收 DTO／值对象，拒绝改归属／自然键；批量 CAS 后清理并重新读取，不缓存共享实体，不暴露实体给浏览器。新 Repository 不提供通用 save/delete，关闭模式及所有集团限定方法另作回归。
- 后续校验：学校代码的空／未知／Unicode 首尾空白、学校与部门种类及父链归属、并发树循环、CAS 冲突、显式清空、成员状态与 epoch 原子性；这些领域场景当前仍待实施，不与本轮存储级验证混同。

独立评审由用户／团队人工完成，尚未记录通过。本轮开发者架构复查没有发现须放宽安全门禁的理由，仍不开放企业 HTTP、多租户管理页面或嵌入业务。真实业务源／React 宿主联调、其他数据库方言、独立 EntityManagerFactory、生产升级与恢复演练未验证。

## 补强后的整包状态

修正低权限外键错误契约后，w03-mapping-reviewed-backend-build-02.log 中 72 项 Java 全部通过，失败／错误／跳过均为 0，随后完整后端 package 成功。生产源码未再变动，复用已成功的 SDK／前端产物；最终 JAR SHA256 为 9d7e41ea3d64783197ed0591ccbeb1e41d0e8212d5d2a1b5899da528851d48d7，专用 PID 1349331。提交前正式回执 fcf69ef3-d110-47bc-8ab6-eb7e03844c00 已通过，不能用第一轮 cf15 回执替代；提交后的正式复验将绑定实际 HEAD 并独立保存 Markdown 交付回执。

## 本单元整合结果与验收方式

提交前正式门禁 fcf69ef3-d110-47bc-8ab6-eb7e03844c00：Java 72、回执自检 20、HMAC 5、社区 API 4、业务数据库隔离 14、基础元库 10、完整应用企业配置拒绝 3，全部通过；实际桌面／移动浏览器 8 项通过，3 类故障控制均捕获预期失败。元库四表仍为空，未生成默认用户、集团或资格。源码／工具／JAR／运行 PID 一致性由门禁校验；回执是本次运行结果，时效性仍按正式工具检查。

审核本文件的设计及失败修正、FoundationJpaMappingTest 的 12 个必需用例和 XML，对照 W03 与字典；在授权合成环境执行 verify-delivery.ps1 后确认上述计数及完整包身份。网页入口仅用于本轮社区登录兼容验收，没有新增集团／学校管理界面，不能通过点击当前工作台验收尚未开发的业务权限。最终提交／提交后回执／推送状态见本地 output/w03/foundation-jpa-mapping-delivery.md 与远程 logs/w03-mapping-final-delivery.md（独立证据文件，避免把提交 ID 写入自身提交形成循环）。

## 后续组织规则衔接

2026-10-08，本文后续校验清单中的学校编号／父链／CAS／清空／并发／事务内 epoch 进入[组织领域单元](organization-domain.md)，内部规则与真实合成库回归分别实施；正式管理资格、审计迁移、可信身份、HTTP 请求与业务取数仍待实施。本文 72 项为正式映射交付历史，不作为当前组织单元门禁最低值。
