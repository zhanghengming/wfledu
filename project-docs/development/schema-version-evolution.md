# W03：历史迁移与当前结构校验分离

2026-10-08，W03/T02、R01/R07/R12、ADR-015。状态：本单元代码、完整构建、运行、正式门禁及开发者架构复查已完成；最终HEAD与推送状态见独立交付回执。沿[迁移规范](migration.md)、[W03](../planning/w03-identity-foundation.md)和[字段字典](../technical/mysql8-table-dictionary.md)，不新增需求或验收编号。

## 开发前记录

已读 AGENTS／局部文件检查、remote-workflow、standards、build-and-run、testing、migration、database、W02、W03、导航／路线／追溯、MySQL设计／字典、ADR-015、现有迁移／版本记录／JPA非空合并调用链。远程主目录 /home/data_dev_zhm/dataease-phase1-test/w02-security/source，任务分支 codex/phase1-security-baseline，基准 d0d133e8571df990f05b98d50d4a3ae81911d374；暂存区空，原有25项跟踪修改及两端现场保留。专用PID1372792、完整JAR SHA256 bab988081e91dc3eb7a09d3cc7e9c5ef94ef4f58e20864857c3fcbbbf1120ad8，现有社区端点核验通过；这是变更前快照。

沿已有授权仅使用新合成W03库、专用13306/16379/18100、现有SSH转发及任务Git交付。不改旧服务／目录／业务库，不执行生产迁移或恢复，不删除合成现场。本单元没有正式4.2 SqlBlock、表、字段或企业HTTP；4.2仅是合成库演进夹具，不注册到生产装配。

## 设计与架构预评

将原4.1声明移入独立FoundationSchemaV41，保持四表DDL逐字节不变，并把嵌套集合设为不可变快照。当前FoundationSchema维护当前目标及通用元信息比较，现行生产目标仍等于4.1。历史迁移只取V41；启动FoundationSchemaVerifier独立取当前目标，不再调用历史SqlBlock。未来版本必须新增历史声明／SqlBlock和当前目标，不改V41。共享比较器可修复比较缺陷，不能重写历史DDL；冻结指纹由本轮改动前已编译代码取得，不在新实现中动态计算预期。

InitSqlListener在任何版本组执行前，若存在组4迁移，调用内部组4历史检查。组4是自研独占命名空间，采用连续、无前导零的4.1…4.N，保留全部历史迁移；不改SDK Version或组1/2/3比较行为。计划重复／缺步／错组／非法版本，以及未知新版本、历史缺失／倒退、失败未解决就跳步、重复成功、空状态／非法rank均拒绝，且不新增失败版本记录或执行DDL。组40等不能匹配组4；组4关闭时不新增数据库交互。

历史记录按rank检查：同一步可有多次失败，成功后才进入下一步。新版升级跳过已成功旧步骤，失败版本先重试再执行后续版本。旧程序遇到已登记更高版本拒绝，不猜测降级，不用旧4.1检查冒充当前结构验证。此检查不修复结构、不提供备份恢复或多节点迁移锁；首期仍单节点。

## 验证计划与边界

先在旧实现用真实MySQL、生产版本实体／Repository／非空合并切面和InitSqlListener复现组40前缀误匹配及未知新版未拒绝，保留目标失败记录；新增用例不能以编译缺类或连接失败充当复现。再覆盖空库、4.1存量数据→合成4.2、重复启动、4.2部分DDL提交后失败／重试、当前目标漂移拒绝、冻结DDL指纹和不可变快照、无效计划／历史及其他组兼容。

演进夹具只在新合成库增加明确注释的user探针字段与探针表，证明历史迁移不重跑、当前校验使用新版目标、MySQLDDL部分提交和幂等续跑；它不是正式字典表或4.2交付。正式元库四表保持为空及原结构，未来资格／审计表仍待。新增必需方法进入回执门禁；整体SDK／前端／后端构建、新包运行、桌面移动8项及三类故障控制、提交后复验按固定规范执行。

开发者架构复查及用户／团队独立评审分别留证，后者待人工记录；不自动合入或开放企业服务。本单元不证明身份／权限／嵌入业务验收，也不实现请求就绪控制。

## 已执行的失败复现与修复

| 证据日志（远程logs/） | 实际结果及意义 |
| --- | --- |
| w03-evolution-old-regression.log | 旧实现真实2项均失败，错误／跳过0；组40.1使4.1被跳过，未知4.2未拒绝并执行社区块 |
| w03-evolution-test-fixed.log | 首次编译漏改3个旧构造调用，未执行测试；补齐所有调用方后继续，不计为通过 |
| w03-evolution-test-complete.log | 初轮104项通过，尚未加入未知保留表断言，不能放行最终补强 |
| w03-evolution-undeclared-before-fix.log | 补强后12项中1项目标失败、错误／跳过0，未知de_ent_表没有被拒绝 |
| w03-evolution-test-reviewed.log | 修复后104项、失败／错误／跳过0；包含未知保留表拒绝、近似非保留表允许及重复目标拒绝 |

冻结四表DDL的独立SHA256来自前一交付已编译代码，回归固定该值；嵌套列、键、外键和CHECK集合不可变，CHECK保持插入顺序，避免抽取改变DDL字节。当前目标仍四表，历史4.1不跟随测试目标变化。演进用例使用真实DeStandaloneVersion和生产Repository继承接口、JpaUpdateNonNullAspect及InitSqlListener；不是Mockito版本账本，也不是启动HTTP就绪测试。

## 开发者架构优化复查

- 历史转换和当前完整结构是两个职责。冻结版本不读取可变当前目标，当前验证器不调用旧SqlBlock；初始化依赖经实际类加载和四表指纹回归验证。没有新增可配置宽松比较器或默认放行Bean。
- 对安全命名空间分别检查完整清单和声明内属性。最初只遍历声明表漏掉未知项，已新增只读information_schema清单检查；转义LIKE字面量下划线，非保留近似名称允许，未声明保留对象拒绝。校验失败不自动DDL、删表或改数据。
- 组4采用有限连续版本协议，完整计划／历史先验后执行；不沿SDK宽松版本解析猜测新版本，也不以最大版本取代失败链。保留同一步失败记录与事务外MySQLDDL现场，原表行在重试后仍在。单节点依赖保留，尚无多节点迁移锁。
- 现有版本主键是Integer，预检计算用long并拒绝rank耗尽；企业执行前另守卫其他组之后的溢出。不改社区版本比较、Repository泛型或已执行记录。未来正规迁移须复核全局rank及备份恢复。
- 编译时漏改3个旧构造调用暴露调用方检索不完整；已补齐全源码搜索及整包编译，不把新增测试编译成功当全部调用兼容。测试门禁同时锁定12个名字和回执字段，旧92项或缺名不能放行。
- 仍无正式4.2、资格／审计生产适配器、受控初始化、可信身份、请求就绪或企业HTTP。ApplicationRunner顺序不证明端口期间拒绝，后续必须实际HTTP覆盖迁移阻塞／失败／成功，不得以社区首页200代替。

这是开发者复查；用户／团队独立评审待记录，不自动合入或生产发布。真实世外集团组织／业务源／React宿主未接入，其他数据库／多节点／生产升级恢复未验证。

## 回归覆盖与验收入口

| 规则与实际入口 | 必需回归（EnterpriseMigrationEvolutionTest） |
| --- | --- |
| 组40不匹配、未知新版在监听器执行前拒绝 | prefixSimilarVersionGroupCannotSuppressFoundationMigration、unsupportedNewerHistoryRejectsBeforeDdlVersionWritesAndCommunityBlocks |
| 历史DDL独立指纹与嵌套不可变、当前快照隔离 | frozenV41DdlHashesAndNestedDescriptorsAreImmutable、currentTargetSnapshotsCannotMutateFrozenHistory |
| 连续合法计划／历史，失败允许重试，其他组契约保留 | duplicateNonCanonicalWrongGroupAndGappedPlansReject、unknownSkippedRegressedDuplicateAndIncompleteHistoriesReject、repeatedFailuresThenSuccessAndNextFailureRemainRetryable、otherGroupsKeepTheirExistingHistorySemantics |
| 真实空库→4.1→合成4.2，重启跳过成功步骤、保留原行 | realListenerEmptyUpgradeAndRepeatedStartupKeepCurrentTargetAndRows |
| 部分ALTER提交后失败与续跑，不重跑4.1 | partialSecondMigrationRetainsCommittedDdlAndRetriesWithoutV41 |
| 当前结构漂移和未知保留表只读拒绝、近似非保留表允许 | currentVerifierRejectsEvolvedDriftWithoutChangingHistoryOrRows |
| 无效计划／失败链／rank耗尽不新增版本写入 | invalidPlansAndUnresolvedFailuresPreventActualVersionWrites |

从[测试源码](../../core/core-backend/src/test/java/io/dataease/enterprise/foundation/EnterpriseMigrationEvolutionTest.java)对照实际XML及EVOLUTION_REGRESSIONS名字，按[固定门禁](login-regression.md)复验；这项基础设施成果没有新页面，网页仅验社区登录兼容。组4预检约束的是InitSqlListener的SqlBlock与版本写入，不声称社区Hibernate启动前从未进行任何数据库交互；企业自动DDL另由已交付隔离机制负责。

## 本轮逐功能整合与最终架构复查

SDK日志w03-evolution-sdk-build.log、前端w03-evolution-frontend-build.log、后端w03-evolution-backend-build.log均实际退出0；后端package显式enterprise-tests，实际104项、失败／错误／跳过0。Java21与现有依赖缓存，未clean／升级依赖。未单独执行ts:check，不声称修复既有TypeScript基线或前端打包警告；build:flush既有生成影响排除提交。

专用新完整JAR SHA256 ba639472a379e11aca6d2ff143bd19bc4d96b47a96685a0e76ff8b7da89ccf9d，生产新增类核验已打入包；仅更新专用18100，PID1398300，启动日志w03-evolution-app-start.log，既有配置及旧3306/6379/8100保持。18010专用SSH转发已恢复，四表元库保持为空、仅4.1正式历史，无默认身份／集团／角色。

提交前正式门禁841ff675-cefc-4143-9897-fd823f82f83d通过：Java104、回执26、HMAC5、API4、业务数据库14、基础元数据10、完整包拒绝3、桌面移动真实浏览器8；404／遮罩／移动提交阻塞三类控制全部捕获预期失败。提交后必须新HEAD重新执行，最终回执保存来源／工具／JAR／PID和Git摘要，不能永久使用本文快照代替。

整合后开发者架构复查核验：历史不依赖当前目标、完整保留清单和表内属性分别拒绝、实际Repository排序／非空合并路径、失败续跑及原行保留、SDK单向边界、默认开关和旧社区入口兼容、必需名字与当前包证据一致。本单元无新增阻断项；下一步仍需正式资格／审计、受控初始化、可信身份及真实请求就绪。用户／团队独立评审待记录，无自动合入／生产发布。

## 后续正式版本的替代状态

2026-10-08，上一单元的合成4.2保持历史测试用途；[正式组织审计](organization-audit.md)现新增生产定义4.2及五表当前目标，替代本文“无正式4.2／无正式审计适配器”的进度。V41冻结、完整计划／历史预检和当前清单验证保持，管理资格、身份、HTTP就绪仍待。正式审计另用真实监听器及版本账本回归，不将旧合成探针当正式4.2验收。当前门禁120/29/12。
