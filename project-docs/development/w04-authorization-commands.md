# W04第4步：授权配置命令与回读

2026-10-09。依据[工作包](../planning/w04-authorization.md)、[W04设计](../technical/w04-authorization-design.md)、[字段约束](../technical/api-field-constraints.md)、[权限矩阵](../requirements/permission-matrix.md)及[存储增量](../technical/w04-storage-dictionary.md)。对应R04/R05/R06/R08、A07/A10/A11的配置子集、V01/V04/V08；不表示业务决策、SQL过滤或页面已完成。

## 本轮范围及预检

第3步交付后已执行独立存储c54e7b1e-16f9-461f-be59-116665230916和完整门禁4fc41ffc-9ea5-463d-b48e-44f20dabd7eb。本轮再次实时verify-gate通过，HEAD 9bda53d25c2249459057a50eb39a7cc32413dd01、源码／工具／JAR／两PID一致；据用户指令继续第4步，不重复把历史报告描述成本轮新测试。

唯一开发主目录为远程`/home/data_dev_zhm/dataease-phase1-test/w02-security/source`，分支`codex/phase1-security-baseline`；本地协作副本不覆盖产品源码。暂存区为空，原25项修改及未跟踪材料保留，相关目录没有局部AGENTS。预检与55份社区／自研代码摘要保存在专用logs/w04-step4-before.json。已读全局规范、远程／构建／测试／自动验收、W02、技能、W04、字段／API及实际事务／认证／JPA实体。旧3306／6379／8100和原目录不动，只使用授权的13306合成库、18100／18120；按受限执行器整包，不修改全机配置或自动扩大额度。

## 最小实现契约与源码复核

- 新增SDK PermissionContract／PermissionManagementApi，服务端放permission/manage及server，条件装配沿现有ManagementConfiguration；SDK不依赖实体。新增局部JSON转换器，只接受明确record及嵌套白名单，原平面DTO／社区Jackson不变；重复键、未知字段、显式null、非字符串ID、额外JSON及64KiB／深度16边界均拒绝。
- 五个POST路径：permissions/catalog、permissions/rules/page、permissions/batch、admin-capabilities/page、admin-capabilities/batch。全部同时加入精确路由与集团作用域；全部要求当前MANAGE_AUTHORIZATION，管理资格不隐授数据查看。旧业务／分享／文件入口仍关闭。
- 主体USER的id为全局用户ID，经当前集团成员映射subject；ORG／ROLE按本集团对象定位。回读不创建主体、不修复归属。停用主体可回读历史配置，实际授权状态由后续决策检查；配置不能靠角色名称取得资格。
- DATASET必须有实际CoreDatasetGroup且nodeType=dataset；它没有orgId，归属取受控EnterpriseResource侧表。DASHBOARD/TEMPLATE/SCHOOL_COPY映射为DASHBOARD+resourceKind并核对实际原生看板及归属；未存在的W06资源不自动创建。目录、孤立侧表、未知／错类型／跨集团引用拒绝，不返回SQL、样本或组件配置。
- 公共普通UPSERT沿现有示例支持省略status，明确解释为ACTIVE；内部实体／迁移默认DISABLED保持。显式null拒绝。管理能力UPSERT按已定字段要求status必填。此为新接口默认值细化，不把数据库默认值当API授权行为。
- 普通自然键包含schoolScope.kind（冻结4.6唯一键）；更新不替换任何自然键，仅完整替换EXPLICIT学校及状态。更换范围类型必须同批DELETE+新增。EXPLICIT非空、最多500、总学校引用最多5000；整批1—200条且字节上限同时生效。批内重复grantId／新增自然键拒绝；ALLOW／DENY独立。
- 学校引用批量加载同集团节点与祖先，验证SCHOOL、ACTIVE及有效祖先，不为每条规则重复查询学校。学校回读独立分页返回total/complete，规则第一页不把最多100个学校声明为完整集合；后续页面及学校页绑定expectedEpoch，学校页另绑定规则expectedVersion。
- 写命令复用ManagementTransactions的用户→集团锁序：重新鉴权→校验幂等／epoch→按需事务内创建ORG主体→校验全部变更引用和version→先删除并flush→新增／CAS替换学校→提升一次epoch→管理资格不变量→审计→DONE幂等结果，整体短事务。删除和创建相同自然键不会被JPA插入先于删除的顺序破坏。中途失败必须回滚全部根／学校／epoch／审计／结果，不保存半完成IN_PROGRESS。
- 幂等规范化包含操作、主体、expectedEpoch、全部命令及排序学校集合；命令顺序保留。重放先核当前会话与MANAGE_AUTHORIZATION，再同键／摘要／期限匹配；命中返回原结果和提交epoch、当前epoch，不再次写入或因旧expectedEpoch重复执行。同键不同内容／过期／非DONE拒绝；24小时窗口，不删除或复用过期键。
- 管理能力独立配置七种集团能力，禁止平台资格／学校范围；删除或停用最后有效授权管理员整批回滚。业务权限默认拒绝求值和来源预览仍是第5步，不能用成功配置推断用户已可取数。

这是开发者设计复核，不是独立人工评审。复用18表／4.7，不新增迁移或修改冻结DDL；新增类型化审计事件沿原统一审计表。

## 完成及自动验收标准

真实Servlet／MVC／OSIV／Spring事务／MySQL验证允许、两个方向的主体／学校／资源拒绝、嵌套JSON拒绝、三类主体、实际dataset／folder／孤立资源、分页完整性及修订冲突、重复自然键和DELETE+新增、CAS、失败前后全表状态、幂等同内容／异内容／并发／过期／撤权、最后管理员、组间／用户间／操作间幂等隔离。具名Java方法和真实整包HTTP caseId必须进入门禁，不能只提高总数。

本步完成设计→编码→架构复核→局部验证→受限SDK／前端／后端整包→新包接口及完整门禁→自有提交／新HEAD门禁／推送→独立用户流程及完整门禁。交付回执与复验另以新Markdown和runId记录，不复用第3步成功报告。独立人工评审仍由用户／团队负责。当前状态：设计、编码、开发者复核、230项Java真实回归、受限SDK／前端／后端整包及候选完整运行门禁通过；Git交付与交付后独立复验的最终状态见本步回执。


## 实际编码与局部回归

新增SDK两文件、permission下严格JSON／Server／Commands／References／ReadService／BatchService六类，复用当前框架和统一响应。现有共享事务补加锁refresh，异常Advice补新包范围。普通回读使用id/version，批量命令grantId引用该id；学校页complete仅表示本次包含全集合，hasNext单独表示下一页。详情以[接口字段补充](../technical/api-field-constraints.md)为准。

三轮局部真实回归记录：3f07b8b5718b47ebb528c1d370735280为10项中3失败；60e763cddc14445ebe09b058ce563d26为10项中2失败；修复后6a7b6d8c558843c0ab426bc3bdf94e0e十项全通过、无跳过。保留失败记录。根因及优化见[实现复核](w04-design-review.md)；错误映射和锁前旧实体是实现遗漏，JSON地址／学校／过期是夹具错误，不混写成产品已通过。

自动门禁已加入230项Java、108项具名新包配置流程、66项回执拒绝自检。独立权限验收主动使旧成功失效，完整verify-delivery随后重新跑所有接口、结构／隔离、存储、资源、真实浏览器和故障检出。其数量不表示执行完成，最终状态由新runId交付和独立复验回执更新。

## 架构复核后的边界修复

部门的schoolId不总能通过parentId找到，权限引用现已分批加载关联学校和全部祖先，再复用既有OrganizationHierarchy规则。旧实现新增回归41e9250a7c984e01b2b11dc1c78967c7失败（70002预期、0实际），修复后33c6ada47ae644ec95af818fdbca8f45通过。首轮230项整包通过只覆盖此前用例，最终修复已重新受限整包并以新包通过候选完整门禁，不使用旧包交付。

只有DELETE的批次允许清理本集团停用／失效主体既有规则；含UPSERT要求主体可用。操作仍重新鉴权并核对归属与CAS，最后管理员不变量保持，不创建新主体。学校／部门关联和停用主体清理已加入108个具名整包案例。复核与根因见[架构记录](w04-design-review.md)。

累计门禁修正：新配置用户流程保留合成规则，正式控制库的存储验收改为回归前后完整内容保留；兼容库仍检查为空。45个具名存储案例、34项迁移／JPA场景继续执行，不清空记录，不降低门禁。失败与根因见[架构复核](w04-design-review.md)。

## 最终包的候选整合证据

最终整包runId a922d56d-8661-4080-baa9-645581fc58b5，SDK／前端／后端退出码均0，24个suite共230项Java无失败、错误或跳过；JAR SHA256为75a5a3b62cdf937afc890a080d6ec570dc8298bc0fd17c1cda9a9c167874f794。候选完整门禁1ed6c28c-f990-4dfc-abf7-dd37c5507c19通过，包含108项新授权流程、45项存储、66项回执防伪、7项资源保护、W03／角色任职既有回归，以及桌面／移动登录8项与3类故障检出。修正后的存储保留检查已实际通过。

源码提交／新HEAD门禁／自有分支同步及交付后独立新runId见[交付回执](../../output/w04/w04-step4-delivery.md)、[独立验收](../../output/w04/w04-step4-post-delivery.md)。最终架构优化复核单独记录于[复核回执](../../output/w04/w04-step4-architecture-final.md)。后续权限求值、实际SQL、编辑、嵌入、页面及任务不在本步验收范围。
