# W04授权实现的技术细化

版本v0.3，2026-10-09。**第2步角色／任职、第3步业务授权与幂等存储已交付并复验；第4步授权配置命令已编码并通过最终整包及候选完整门禁，Git交付及独立复验状态见回执，见[第4步记录](../development/w04-authorization-commands.md)。第5步权限求值／预览、第6步完整撤权复查仍待实施。** 本文记录W04工程决定和社区源码接点，不替代[权限矩阵](../requirements/permission-matrix.md)、[API契约](api-contracts.md)、[字段约束](api-field-constraints.md)和[MySQL字典](mysql8-table-dictionary.md)。任务及证据入口见[W04工作包](../planning/w04-authorization.md)。

## 1. 本次源码事实

调查对象为远程任务HEAD `0d0edb789766d98d99974485242009bc6e821970`，W03产品提交 `473db8b63c1e7b5bc70e8d084a7d6ec8f20fd10d`。仅阅读社区／自研源码，没有读取或复制de-xpack实现。

以下源码路径均相对远程任务source目录；它们是已核对入口，不表示本轮改过代码。

| 当前入口 | 已有行为 | W04处理 |
| --- | --- | --- |
| `core/core-backend/src/main/java/io/dataease/enterprise/management/persistence/EnterpriseRole.java`、`EnterpriseRoleAssignment.java`、`EnterpriseAssignmentSchool.java` | 4.3存储，任职自然键为集团＋成员＋角色，学校集合独立 | 复用实体；补公开配置与不变量，不重复建角色表 |
| 同包 `EnterpriseSubject.java`、`EnterpriseAdminGrant.java` | ORG/ROLE/USER类型化主体及七类管理资格 | 复用主体登记；管理能力与数据策略分别求值 |
| `enterprise/management/manage/ManagementAuthority.java` | 事务内实际管理资格、禁止优先、状态检查；ROLE需唯一任职且学校均可用 | 保持现有管理语义；业务权限独立纯规则，不把管理布尔值当数据范围 |
| 同包 `ManagementTransactions.java`、`GroupAdministrationInvariant.java` | Spring绑定EM／真实事务，用户／集团锁、修订校验、最后有效授权管理员 | 批量授权复用短事务；角色／任职／资格及成员关系变化纳入不变量 |
| `enterprise/management/server/ManagementRequestFilter.java` | 精确路由白名单；只给members／organizations／resources打开集团作用域 | 新路径必须同时加入路由及集团作用域分类；保留finally清理和旧入口关闭 |
| 同包 `StrictManagementJson.java` | 局部新DTO转换，流式64KiB上限、深度16；只接受简单String／Integer／字符串数组 | 新嵌套DTO需要递归结构白名单；不能沿用字符串数组逻辑或修改全局Jackson |
| `enterprise/foundation/FoundationSchema.java` | 普通列描述；比较EXTRA只接受空或DEFAULT_GENERATED，未读取生成表达式 | 扩展生成列描述和精确校验，再实施策略自然键；历史DDL输出不变 |
| `enterprise/management/manage/ResourceOwnershipService.java` | 实际空白原生看板＋归属；读取目前仅平台GROUP_READ_ALL VIEW | 在同一受控读取入口接入普通资源VIEW，不解除空白载荷约束、不开放编辑／取数 |
| `sdk/common/src/main/java/io/dataease/dao/auto/entity/CoreDatasetGroup.java` | 实际数据集／目录模型，nodeType为dataset或folder；不是core-backend下同名文件 | DATASET核对真实dataset节点及归属；目录／物理表／字段ID拒绝 |

所有新增公共DTO／API放在sdk/api/api-permissions，核心负责实现；纯领域规则放在明确的permission/domain位置，事实加载及事务编排放在permission/manage，HTTP在server。已有management实体不因目录名移动或全面重构。SDK不依赖核心Entity／Repository。

## 2. 身份、任职和管理能力边界

集团从已验证管理会话解析；普通请求禁止tenantId和伪造用户头。`subject.type=USER`的id仍为平台用户ID，服务端解析本集团成员，不能改成memberId。角色code创建后不可改，任职UPDATE不换成员／角色；同角色多学校通过同一任职学校集合表达，不建立重复任职。

ACTIVE任职必须引用同集团有效成员、角色及1—500个可靠学校；DISABLED允许空学校集。集合完整替换，空EXPLICIT不能表示全部。角色停用使后续匹配失效，不批量删除历史任职或策略。

管理资格仍是集团级独立能力。有效MANAGE_AUTHORIZATION可配置本集团三类主体的业务策略及集团管理能力，不要求管理员本人能读取被授权业务数据；始终不能配置平台资格或跨集团委托。

**防间接提权工程约束：** MANAGE_ROLES不足以把自己任职到带管理资格的角色；MANAGE_MEMBERS不足以把自己加进带管理资格的组织。所有可能改变有效管理能力的角色状态、任职、成员组织关系及组织状态路径，比较受影响成员变更前后的管理能力；发生能力变化还须验证操作者变更前拥有MANAGE_AUTHORIZATION。不能以修改后获得的新资格鉴权。纯名称修改无需追加此资格。安全变更同时检查最后有效授权管理员；失败整事务回滚。下一编码单元必须先确认影响集合及现有组织内核的可用扩展点。

现有管理资格的“学校均可用”与业务范围求值分开：业务任职范围取可靠有效学校的交集，停用学校不得带来范围扩大；不能因为一个无效学校退化成ALL。各自结果和停用边界分别测试。

## 3. 策略与决策

普通策略仍采用DATA_ACCESS与RESOURCE_ACTION两种类型。数据集只支持VIEW/EXPORT/DRILL；集团看板／模板用NONE，学校副本匹配所属学校；EDIT只属于资源动作。角色集团资源NONE只匹配有效任职来源，不扩大该看板图表的数据范围。

纯领域求值接收不可变、同版本的可信事实：集团／身份修订，有效成员及显式组织关系，角色及成对任职，学校可靠归属，策略及真实资源类型／状态。不要让领域函数执行JPA查询、Redis调用、SQL拼接或根据角色名称判断。

按矩阵逐条展开来源：每项角色规则先与该项任职学校求交，再并集ALLOW、并集匹配DENY并相减；ORG只匹配明确成员关系，不自动父子继承；USER增补不覆盖全部继承。最终再与当前集团有效学校求交。EXPORT/DRILL结果必须与VIEW求交；资源EDIT也须VIEW，操作允许不自动授予前置权限。

平台GROUP_READ_ALL独立处理：有效专属身份只能逐集团VIEW，普通个人禁止不取消该已确认例外；不隐授EDIT／EXPORT／DRILL。非VIEW仍须有效本集团成员及对应普通授权。资源／学校／源门禁不能因平台例外跳过。

决策返回allowed、内部reason、可信集团／用户／资源／动作、最终学校及来源、accessEpoch／identityEpoch／资源版本。来源包含grantId、subject引用、assignmentId及匹配学校；仅授权管理员预览可见。预览目标身份只作为显式事实参数，不能替换当前请求操作者或绑定新的ThreadLocal。

预览输出增加阶段事实：`authorizationAllowed`／`allowedSchoolIds`表达策略决策；`executionReady`／`pendingChecks`表达取数门禁。W04未完成源／学校字段绑定时executionReady=false，不能将策略允许宣称成可执行查询；ResourceAction的集团NONE不借空学校列表解释为全量数据。

## 4. API与严格解析方案

沿用 `/de2api/api/enterprise/v1/`，配置接口均POST JSON，Bearer身份、Origin、统一响应及错误机制不变。正式SDK发布前同步OpenAPI和精确字段清单。

| 接口组 | 输入与主要要求 |
| --- | --- |
| roles/save、page | 沿用mode／id／expectedVersion分支，code／name／status；分页只查本集团 |
| assignments/save、page | memberId／roleId／完整schoolIds／status；返回配对范围与version，不返回学校和角色的独立并集 |
| permissions/catalog | subject、resourceType及分页；只返回本集团可管理的学校／资源元数据，不返回业务样本 |
| permissions/rules/page | 主体、筛选及分页；返回grantId/version/epoch；学校范围大于单页时标明不完整，按规则版本读取学校页 |
| permissions/batch | 单主体、expectedEpoch、idempotencyKey、1—200条类型化UPSERT/DELETE；学校引用合计≤5000 |
| admin-capabilities/page、batch | 独立DTO，七种集团管理能力，不接受学校／资源范围或平台资格 |
| permissions/preview | userId、policyKind、真实resource及action，输出来源／学校／修订和执行准备状态 |

未知字段、重复JSON键、错误UTF-8、尾随JSON、非对象嵌套、数字ID、浮点版本、null项、重复ID、非法枚举和跨字段组合在新DTO局部拒绝。递归解析依据明确record／变体白名单，不根据任意反射类型自动放行。CREATE禁id/version；DELETE只允许grantId/version；NONE、ASSIGNMENT及动态范围禁携带ids或resourceId。

目前W03实装请求体上限是64KiB，总体草案256KiB不是现状。W04先保持64KiB、深度16、页大小≤100和现有字符串边界；条目／学校数量上限与字节上限同时适用，超出任一明确拒绝，不能部分执行。不为达到理论200条而无验证调大上限。

采用现有ResultCode和DEException；参数、无身份、无权限、资源安全失败、版本／重复键冲突沿既有W03行为，内部SQL／事务细节不外露。新增稳定原因投影仅限W04 DTO，编码时对照实际ResultCode数值和ManagementExceptionHandler验证，不修改全局响应或擅自承诺HTTP409。

## 5. 保存、幂等、事实加载与撤权

写请求在身份和集团作用域中进入现有 owning JPA事务，按现有用户→集团锁序重新鉴权，核对expectedEpoch／聚合version及同集团引用，校验全部变更，再执行CAS／学校集合显式替换。自然键冲突拒绝；ALLOW与DENY可以并存，修改不换策略自然身份，调整自然身份用同批DELETE＋新增。未提交事务内的同自然键替换必须明确flush顺序并加真实MySQL回归。

同一事务提交规则／任职／管理能力、版本、集团epoch、审计和成功幂等结果；任何失败全部回滚。不要用Repository非空合并实现集合清空，不捕获数据库异常后继续提交。集团锁串行不能替代expectedVersion检查；同批相同grantId或新增自然键重复拒绝。

幂等作用域为当前集团＋操作者用户＋稳定操作名＋随机键；规范化摘要包含主体、版本／epoch、全部命令和完整学校集合，集合排序只消除无业务意义的顺序，不改字符串大小写或空白。

重试先核验当前会话和当前管理资格，再读取已提交幂等记录：同键同摘要返回原安全结果元数据，**不再次写规则或提升epoch**；同键不同摘要拒绝。命中原成功后不因旧expectedEpoch而再次执行；原提交epoch与当前epoch分别返回，旧结果不是授权凭据。首期保存窗口默认24小时，过期明确拒绝，不在本包自动清理或复用旧键。失败事务不保留半完成IN_PROGRESS成功结果。

只存操作结果ID／版本／提交epoch，不存凭据、Ticket、业务数据或完整请求。总体字典response_ref的255字符不足以保存200条新grant结果，W04候选字典补result_metadata有界JSON，见[字典补充](mysql8-table-dictionary.md#w04-storage-design)。重试仍重新鉴权；后续APP分支另迁移，不提前创建嵌入应用依赖。

事实加载在短读事务中校验用户／集团修订和状态，批量加载只属于目标成员／主体／资源的引用并生成不可变事实；避免逐学校查询和全集团授权全量加载。需要分页读学校或预览来源时每页绑定expectedEpoch／规则version，不跨修订拼接。预览不得写审计成功变更、创建subject或隐式修复归属。

W04首次不缓存权限决策，后续请求重新加载权威事实。身份或集团修订改变时丢弃旧决策，失败拒绝；管理员撤权后保留会话的后续请求重新求值。查询前／交付前／流式块的协调器及源修订在W05/W09继续实现，不能用W04短读锁宣称已解决外部SQL交付竞态。

## 6. 存储、生成列与迁移次序

复用4.3六表、4.4身份会话及4.5资源归属。新增候选4.6为de_ent_grant／de_ent_grant_school，4.7为USER分支幂等存储；执行前先新增生成列表达与验证支持，注册连续组4迁移、当前目标及实体。4.1—4.5冻结DDL和历史指纹不得变化。

生成列resource_key／principal_key已按隔离MySQL实测扩展描述与校验：STORED、EXTRA、元数据可空性、默认及索引分别严格核对，DDL表达与观察到的元数据表达分开记录，表达式逐字比较，不套用CHECK规范化或lower／trim。生成列JPA只读，浏览器无对应字段。当前实施范围与字段说明见[存储增量](w04-storage-dictionary.md)，实际整包结果见[存储记录](../development/w04-authorization-storage.md)。

在专用合成库证明空库、4.5带行升级、重复启动、部分DDL已提交但失败账本保留的重试；反向外键及非法组合拒绝。未知de_ent表／视图仍拒绝，新版不自动ALTER漂移；不根据上游orgId猜测存量归属，不回填默认允许策略。升级后旧W03程序不一定可启动，回退使用兼容新历史的包或经明确授权的备份恢复，不能删除账本降级。

W04业务catalog、preview和普通VIEW的DATASET测试夹具必须同时建立实际CoreDatasetGroup dataset节点及同ID归属，不用虚构ID／目录验证“数据权限通过”；登记只在受控合成夹具进行，正式源／数据集归属绑定由W05补齐。第3步存储FK测试使用合成归属记录，仅证明类型与集团关联，不证明业务数据权限或实际取数。catalog与preview不返回SQL／样本，也不从策略允许自动开放旧图表接口。

## 7. 三层验证与对后续包的契约

每项同时验证纯领域规则、真实JPA／Servlet及完整产品旧认证／MVC／OSIV链，沿W03漏测整改；新增方法名和HTTP caseId加入门禁，不靠数量增长。保留社区兼容登录、桌面／移动真实浏览器与三类故障检出。

W04必要负例包含：学校角色交叉、ORG父级隐式继承、个人禁止仅扣匹配操作、EXPORT无VIEW、假平台角色、跨集团主体／学校／资源、混合批次回滚、同自然键重建、不同内容幂等重放、撤权后重试、通过组织／角色任职提权、最后管理员移除、预览伪造身份及分页跨修订。

W05消费当前AuthorizationDecision并加资源依赖／源绑定／学校字段和执行交付复查；W06加受限模型命令；W07再加用户×App交集；W08用相同API实现配置页；W09贯通任务和文件。授权结果只作内部可信输入，不接受浏览器上传的最终范围或摘要。


## 第4步落地契约补充

新增PermissionContract／PermissionManagementApi；permission/server与manage复用既有ManagementTransactions、管理能力和4.6／4.7表。五个精确路由全部开启集团上下文和MANAGE_AUTHORIZATION检查；独立严格嵌套JSON语法不改变旧DTO或社区Jackson。字节严格UTF8、64KiB、深度16与批量边界同时检查。

USER主体使用全局用户ID，经集团成员定位；ORG主体首次写时在本事务创建，读不创建。DATASET的原生实体无orgId，必须依赖已登记的集团归属并存在真实dataset节点。模板／副本校验当前原生对象及侧表关系，不在本步创建W06资源或开放编辑。资源目录只返回元数据，包含状态供配置使用；写入再次核验引用可用性。

列表记录沿现有管理页使用id/version；批量请求grantId引用该id，批量结果使用grantId/version。规则返回schoolScope.kind/ids/total/complete，普通页只返回最多100个显式学校；独立学校页使用grantId/expectedVersion/expectedEpoch/schoolsPageNum/schoolsPageSize，complete仅表示此响应包含全部集合，hasNext表示还有页。三种列表第2页起必填expectedEpoch。

普通UPSERT省略status时ACTIVE；显式null拒绝。管理能力UPSERT仍要求status。自然身份包括schoolScope.kind，更改必须同批DELETE＋新增；已有学校集合完整替换。先验证变更、明确删除flush顺序，CAS写根、学校、一次epoch、最后管理员校验、审计及DONE结果同一事务，失败不留下成功记录。首次ORG主体的事务内插入亦在失败时回滚。

幂等重放仍先鉴权，按集团／用户／操作／键隔离，24小时期限；同摘要返回原commitEpoch及当前epoch，不因旧expectedEpoch重复执行。共享事务加锁后refresh集团实体，避免认证校验先加载的旧实体影响并发判断；旧上下文可被拒绝，随后合法重试读取已提交结果。独立验收及完整门禁详见本轮交付文件；局部回归不能替代整包。

组织引用统一采用分批事实闭包＋OrganizationHierarchy，闭包包含显式schoolId及祖先，避免parentId单路径遗漏集团直属但归属于学校的部门。删除已停用主体历史规则只复核归属及版本，不以重新激活主体作为撤权前提；UPSERT仍要求当前可用。该修复以旧实现失败和新实现通过留证，最终重建包已通过候选完整门禁。

## 第5—7步实施契约补充（2026-10-09）

正式实现为PermissionDecision、PermissionFactLoader和PermissionDecisionService，SDK新增permissions/preview。预览字段、求交算法、平台资格及执行范围以[权限决策记录](../development/w04-permission-decision.md)为准；阶段步骤和相关测试以[连续实施记录](../development/w04-remaining-execution.md)为准，完整结果见最终回执。AuthorizationDecision在既有设计中是逻辑概念，本轮实际类型为PermissionDecision.Result/Facts；后续消费实际类型，不再建平行求值器。

数据策略、管理资格、资源归属和执行就绪分别判断。VIEW前置采用实际有效查看结果，包括明确平台逐集团查看资格；非VIEW仍要求本集团有效成员和单独操作规则，操作禁止优先。组织变更保护追踪parentId与显式schoolId双关系；没有授权管理能力的人不能通过停用学校移除部门禁止。事实加载器注入管理事务使用的工厂，核对真实绑定EntityManager和集团锁，不能直接比较Spring代理与原生工厂。

W04不新增策略缓存、数据库版本或消息中间件；来源扩展有界并在超限时整项拒绝。真实SQL、源绑定、模板副本、嵌入上限、矩阵页面、文件和异步撤权仍分别在W05—W09完成。本轮独立自动验收不等于用户／团队人工评审。

## W04同类缺陷整改架构补充（2026-10-09）

创建／启用校验与撤销／清理校验分开：成员DISABLED仍检查身份存在及同集团CAS，但不要求全局用户重新启用；ACTIVE继续要求全局身份有效。SCHOOL_COPY依赖校验复用TEMPLATE的原生对象及归属检查，不只检查侧表；失效资源的历史授权仍可DELETE。构建与交付统一消费product-inputs.py的完整输入清单，根POM、SDK/core新增文件及相关配置纳入摘要。原因、修复与后续W05—W09约束以[整改记录](../development/w04-defect-followup.md)为准，实测以对应最终回执为准。
