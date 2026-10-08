# W03：正式组织变更审计

2026-10-08，W03/T02、R01/R07/R12及PRD必要安全记录，继承ADR-012/015。状态：实现、完整构建、专用新包运行及提交前正式门禁已通过；最终提交后复验和Git交付以独立回执为准，W03整体仍进行中。主出处为[字段字典第33表](../technical/mysql8-table-dictionary.md#de-ent-audit-event)、[迁移规范](migration.md)、[组织事务](organization-domain.md)及[版本演进](schema-version-evolution.md)，不建立独立审计产品。

## 前置记录与范围

已读根及局部AGENTS检查、remote-workflow、standards、build-and-run、testing、W02、W03、开发计划、导航／路线／追溯、PRD、字典／MySQL设计、迁移、组织内核及实际JPA／版本监听器。远程主目录为 /home/data_dev_zhm/dataease-phase1-test/w02-security/source，分支 codex/phase1-security-baseline，基准752aa9be00c7b827dbb05d364feb9beb7db6cb9b；暂存区空，原25项跟踪修改及未跟踪输入保留。本地仅辅助镜像，旧源码、业务库、3306/6379/8100不动。

沿持续授权使用专用13306、16379、18100及新合成库、任务元库、新完整包与任务Git交付。4.2只新增de_ent_audit_event空表，不迁移业务数据、生成默认身份或管理资格，不删除合成现场。新增表进入编译期当前目标；4.1冻结指纹不变。新程序升级任务元库后，旧4.1程序将因较新历史拒绝启动；回退需保留新版或经授权恢复备份，不能删除4.2账本蒙混降级。生产执行／恢复未授权或验证。

管理资格依赖de_ent_subject及角色／任职，不能临时用成员标记或角色名称放行。本单元先闭环正式审计存储／同事务适配；管理资格、受控初始化、可信身份和HTTP就绪另行实现。组织内核仍不注册默认Bean／HTTP，测试资格适配器仍明确标识。

## 设计及架构预评

- 新增冻结FoundationSchemaV42审计声明与4.2 SqlBlock；沿同一显式foundation开关装配，默认关闭无数据库交互。迁移先核对4.1及现存审计表，再仅建缺失审计表，严格后验；不重新执行4.1、不自动修复漂移。历史组4的失败记录与续跑仍由真实Repository／监听器负责。
- 第33表13列，JSON明细非空，范围GLOBAL/TENANT与actor USER/SYSTEM分别检查明确空值分支，正数引用、稳定非空代码及明细对象／4096字节上限进入强制CHECK。tenant/actor FK为RESTRICT，普通管理命令不能删除审计。字段／注释／索引以字典为准；不是将四表的公共版本／更新字段套到事件表。
- 事件实体为独立JPA映射，assigned Snowflake ID，无通用Repository或CRUD。组织适配器只写TENANT/USER/SUCCESS，固定事件代码、资源类型和明细version，不接收任意JSON、业务名称、密码或Token。Trace由未来可信请求入口提供，当前只要求构造时显式传入受控来源；没有默认空Trace。
- 追加要求当前AccessContext和同一工厂绑定的实际Spring事务／EntityManager；集团、操作者、新epoch及操作均和内核Change匹配。persist与组织／集团epoch同事务，失败整体回滚，不用afterCommit或REQUIRES_NEW。拒绝审计的独立短事务未来接HTTP后实现，不能称本单元已覆盖所有审计事件。
- 实体不可变且拒绝EntityManager.remove，避免普通实体更新／删除；不承诺防止数据库特权账号或开发者直接bulk SQL。生产运行／迁移／归档账户权限分离仍需生产部署方案，不在本轮修改测试账号或旧实例权限。

## 验证契约

真实MySQL／Boot/JPA与生产监听器覆盖：4.1→正式4.2空库／存量行保留、重复启动；建审计表提交后失败／重试；结构漂移不自动修复；CHECK空值组合、JSON与FK；默认关闭及新包5表空数据／版本记录；组织新增／修改与正式审计原子提交、审计插入后失败回滚、双集团内容分离及缺上下文／错actor／错epoch／错事务拒绝。历史冻结指纹和原104项继续保留，不以减少旧覆盖换取新测试通过。

新增必需名字和回执字段纳入固定门禁，执行整体SDK／前端／后端、仅重启专用进程、桌面移动8项及3类故障控制、提交前后新HEAD门禁。设计预评和整合后开发者架构复查分别记录；用户／团队独立评审仍待，不自动合入或生产发布。未实现企业入口／管理资格／真实世外数据与React宿主／多节点／生产恢复保持待验证。

## 已执行的失败、修复和回归

| 远程logs中的日志 | 实际结果与处理 |
| --- | --- |
| w03-audit-test-first.log | 120项，失败0／错误53／跳过0；最新结构夹具被审计CHECK规范化差异阻断，不是53个独立业务错误 |
| w03-audit-test-fixed.log | CHECK声明修正后，120项，失败4／错误4／跳过0；真实事务被工厂代理与原生对象直接比较误拒绝 |
| w03-audit-test-transaction-fixed.log | 120项，失败1／错误0／跳过0；数据库正确拒绝外键写入，但测试只接受1452，实际返回1216 |
| w03-audit-test-reviewed.log | 修复断言后120项，失败／错误／跳过0；包括原104项和正式审计16项 |

只修改尚未在任务元库应用的4.2草稿声明，不改冻结4.1、不放宽结构比较器；已建失败合成库／版本账本及原4.2草稿保留，不自动修复或DROP。CHECK保留布尔分组，JSON上限改为直接LENGTH(details)，真实ASCII及UTF-8多字节测试证明按字节拒绝。事务校验以指定工厂资源绑定的同一个EntityManager及实际／已加入事务为依据，不把Spring工厂代理和Hibernate原生工厂当同一对象。外键断言要求DataIntegrityViolationException、SQLSTATE23000和1216/1452明确错误码，不接受任意失败。

## 开发者架构优化复查

- **版本边界**：历史4.1不可变，正式4.2仅新增审计表；当前五表目标独立，完整保留清单继续拒绝未知结构。已有成功历史不重跑；真实监听器／Repository／切面测试保留失败后续跑记录。
- **事务边界**：组织内核拥有事务，审计显式加入同一资源，不开启另一事务或afterCommit。新增权威标量查询核对组织版本、集团新epoch和有效操作者，避免bulk CAS后读取旧受管实体，也拒绝仅形状合法的Change。追加失败向上传播并回滚组织／epoch／审计。
- **审计载荷**：无通用CRUD、任意JSON或业务名称；只保存稳定事件代码、ID和版本，可信Trace来源留给正式身份入口。Immutable／PreRemove保护普通实体路径，不能代表数据库特权或bulk SQL防护，运行／迁移／归档账号分离仍待。
- **权限依赖**：没有默认Authority、审计Bean或HTTP。测试资格适配器和生产审计适配器分别声明；不能在正式资格缺失时用临时管理员标记、角色名称或可选Bean放行。
- **验证边界**：16个审计精确名称和auditRegressions字段进入固定回执；最低120／29／12，不接受旧104／26／10回执。提交改变HEAD后重新跑正式门禁，实际整包与运行包须对应本轮源码；社区页面通过不等于企业管理功能通过。

整包后已复核同事务调用、工厂代理与绑定资源、权威标量查询、默认关闭／JPA隔离、冻结4.1与五表当前目标、16个审计必需名称和新包一致性，未发现新增阻断项。用户／团队独立评审另记，不以开发者复查代替。

## 验收入口与范围

核对[正式迁移8项](../../core/core-backend/src/test/java/io/dataease/enterprise/foundation/AuditMigrationTest.java)和[正式审计8项](../../core/core-backend/src/test/java/io/dataease/enterprise/foundation/OrganizationAuditTest.java)的实际XML，不只看Maven成功文字；按[正式门禁入口](../../tools/phase1/verify-delivery.ps1)使用既有Playwright模块复验。新任务元库应为五张空表、4.1和4.2成功记录，无默认用户或授权。网页暂可验社区桌面／移动登录兼容，没有新增集团／学校管理页面。

W03仍待管理资格／主体／任职、受控初始化、可信身份与请求就绪，拒绝审计亦待HTTP边界；后续统一授权、数据查询、导出、缓存、文件、任务、嵌入和真实React宿主联调未验收。A01–A33／V01–V10／P01不提升为整体通过。生产升级／备份恢复、多节点、数据库特权防篡改与审计归档未验证。

## 整合结果与交付关联

SDK日志w03-audit-sdk-build.log、前端w03-audit-frontend-build.log、后端w03-audit-backend-build.log均退出0；后端package实际120项，失败／错误／跳过0，Java21、既有缓存，不clean／升级依赖。仅运行新完整JAR，SHA256 fc5cc47b6c939aadd3a476ec601f48c87191c038c58751fd52b628f89d971bef，PID1411202，启动日志w03-audit-app-start.log。任务元库4.2成功，五表／56列／15FK／23强制CHECK／28索引／注释／排序规则及空表12项只读检查通过，正式4.2定义自此冻结。

提交前门禁559ad4aa-e07c-481f-91b7-213336d06778通过：Java120、回执29、HMAC5、社区API4、业务库隔离14、基础结构12、企业缺实现启动拒绝3、真实浏览器8和故障控制3类，实际XML11个套件且原104项均保留。浏览器故障注入产生预期失败，不算健康回归失败。

交付前检查具体暂存路径、敏感信息、diff --check及原25项修改保护，只提交本单元增量，未跟踪旧PRD／环境／字典输入不整文件纳入提交。提交后必须在新HEAD重跑同一正式门禁并再推送，最终HEAD／门禁／原有修改核对和验收说明写入output/w03/organization-audit-delivery.md及远程logs/w03-audit-final-delivery.md，不能用本文较早门禁永久放行。
