# W03/T02：基础表迁移设计与验证

2026-10-07。本小功能实现四张基础表的正式迁移。集团/学校管理、JPA实体、可信身份解析及资源权限尚未实现。依据PRD R01/R03/R07、MySQL完整字典第1/2/6/7表及ADR-014；设计输入不是业务验收通过证据。结果见末尾实测记录。

2026-10-08评审更正：下方2026-10-07“无新阻断项”自评结论由新评审发现替代。默认值大小写归并和索引前缀遗漏的修复、原因分析及最新验证见[整改记录](schema-validation-review.md)；[ADR-015](../adr/015-schema-validation-and-startup.md)同步精确比较及后续启动架构。4.1 DDL不变，本次不是修改历史迁移结构。

## 范围与前置

唯一开发主目录为远程`/home/data_dev_zhm/dataease-phase1-test/w02-security/source`，分支`codex/phase1-security-baseline`，基准`50a104fa6d79663c603209f9d033717dd4d12502`。本地只作辅助编写及逐文件审阅镜像。保留两端已有文档、AGENTS、前端及生成差异，不访问XPack实现，不修改子模块指针。

已读根规范、远程开发流程、开发/配置/构建/测试/迁移规范、W02环境、W03入口、PRD、ADR-014、字段字典及追溯。源码调查覆盖InitSqlListener、SqlBlock/Version、版本Repository、JpaUpdateNonNullAspect及测试Profile。初始源码只执行1/2/3组，Hibernate在Runner前使用update；Repository非空合并不能证明字段清空或并发控制。

## 设计契约

| 项目 | 本轮行为 |
| --- | --- |
| 表 | de_ent_user、de_ent_tenant、de_ent_tenant_member、de_ent_org；共43列、24索引、13外键、17强制CHECK |
| 迁移 | EnterpriseFoundationSqlBlock：组4、版本4.1；核心只将4追加到1/2/3之后，不重写上游版本 |
| 开关 | enterprise.foundation.enabled默认false，只接受精确true/false；只启用迁移及结构校验，不替代enterprise.enabled或安全装配 |
| 状态 | 集团、用户、成员、学校默认DISABLED；不创建默认集团、管理员、成员、学校或业务授权 |
| 结构 | InnoDB、utf8mb4_0900_bin、正数ID/version/epoch、DATETIME(6)、中文注释；NULL legacy_uid不代表旧管理员 |
| 顺序 | 全局用户（自引用审计FK）→集团→成员→组织；父节点及所属学校为同集团复合FK；集团+用户成员唯一 |
| 重试 | 先检查全部已有目标表；匹配才补建缺失表；不使用宽松IF NOT EXISTS、ALTER自动修复、DROP或自动搬运业务数据 |
| 启动校验 | FoundationSchemaVerifier order=2，每次只读校验，包括监听器跳过已成功4.1的情况；结构漂移拒绝启动 |

FoundationSchema同时生成DDL和表达核对契约，检查列顺序/类型/长度/NULL/default/comment/collation/extra，全部索引及可见性、外键本库目标/字段/RESTRICT、CHECK名称/ENFORCED/表达式。规范化MySQL元信息的字面量转义、字符集引入符及外层括号，保留内部逻辑分组和字符串大小写。额外列/索引/约束也拒绝，不静默接管已有同名表。

本轮不注册新JPA实体，避免ddl-auto:update抢跑正式SqlBlock。下一小功能先解决企业实体自动DDL隔离，再映射正式表。JPA乐观锁、save(null)、组织环、父链学校一致、目标kind=SCHOOL、集团开通条件、epoch更新仍待领域事务服务实施，不因建表成功变成已验证。

## 验证及环境保护

既有3306/6379/8100、旧源码/运行目录/库/服务全部保护。本轮仅用任务13306/16379/18100和专用文件。新测试账号de_phase1_w03_test@127.0.0.1只具有de_phase1_w03_*新增合成库的CREATE/ALTER/INDEX/SELECT/INSERT/UPDATE/DELETE/REFERENCES，不授予旧库、平台元库或DROP权限。随机密码只在runtime/conf/w03-client.cnf，mode600。测试固定任务端口及RSA公开密钥，不动态获取公钥。

每项真实MySQL回归创建de_phase1_w03_<scenario>_<12hex>，标记synthetic-only-retain-no-drop，保留现场。任务运行元库只新增4表和4.1版本，上游2.40及原双集团合成业务库保持原状。不让元数据验证代替业务分库账号边界。

| 测试入口 | 覆盖 |
| --- | --- |
| FoundationConfigurationTest（5） | 默认/显式关闭不查询数据库，启用只注册基础迁移，错误开关拒绝；表达式分组及字面量大小写不能归并 |
| FoundationMigrationTest（12，真实MySQL） | 空企业结构及元信息、重复DDL/存量保留、多集团成员、学校全局保留/大小写/前导零、双向跨集团父节点/school_id拒绝、非法学校/状态/epoch/旧身份、审计引用、结构漂移/未强制CHECK拒绝、成功版本后校验、DDL部分提交后监听器重试 |
| 实际应用 | 专用完整JAR、真实版本Repository/监听器登记4.1，重启不重复版本，社区回归及企业缺装配拒绝 |
| verify-foundation.py（10） | 只读核对13306、4表/43列/13FK/17CHECK及注释/排序规则、2.40+4.1成功、4表无自动身份或租户数据 |

重试测试调用真实InitSqlListener.run()和真实DDL，版本Repository用测试适配保存真实测试账本；不是JPA Repository测试，实际应用启动另验证生产版本记录路径。拒绝断言限定MySQL唯一/外键/CHECK/非空错误码，不接受任意SQL或连接异常。

交付门禁要求39项Java（原22+新增17）、11项回执、10项基础元数据，继续保留5项HMAC、4项API、14项业务数据库、3项完整包拒绝（企业缺装配、企业开关错拼、基础迁移开关错拼）、8项浏览器及3故障控制。参与执行的全部工具纳入指纹；缺foundation的旧W02回执不能放行。

## 架构与优化自评

1. SDK契约未变，迁移封装在enterprise/foundation，只登记一个迁移组，无私有实现依赖。当前单节点；未来多节点必须增加迁移独占。
2. 显式迁移开关与安全装配分离，可在受控测试区演练；默认关闭不新增企业表，不能将表开关当作集团功能开通。
3. 同一声明生成DDL/核对信息；全量先验与每次启动只读校验是自评中落实的优化。未来4.2应新增迁移和当前结构验证契约，不改写已执行4.1的DDL，也不把4.1旧结构验证套到升级后的表。
4. 不把跨行层级合法性、并发或授权藏进DDL；后续领域事务/JPA行为单独交付，业务A/P保持待实施。

这是开发者自评。用户/团队独立架构及拒绝路径评审待记录，不描述为独立评审通过，不合入或开放企业服务。

## 恢复与限制

MySQL DDL隐式提交，失败会留表及success=false，不承诺整批DDL回滚。结构匹配时重启补建；结构冲突保留现场人工评估，无自动反向SQL或删除。生产执行和恢复演练未授权/未完成。仅实际验证8.0.46；8.0.16是运行检查下限，不宣称所有补丁实测。

## 实测记录

局部回归logs/w03-foundation-test-06.log：39项，失败/错误/跳过均0。初轮发现测试连接、公钥固定、CHECK元信息转义、最小权限账号外键错误码差异，已修正后重验。整包构建、运行迁移及提交前正式门禁已完成，结果如下；提交后再绑定新HEAD复验及推送。

### 逐功能整合结果及架构复查

- SDK：logs/w03-sdk-build.log；前端：logs/w03-frontend-build.log；后端：logs/w03-backend-build.log，三步实际退出0。前端原有打包警告保留，不作为新失败；未改依赖或锁文件。
- 完整CoreApplication.jar SHA256：4797d2995cdaf907bf1187e994928bedf46f2115c514c2018780f62a370db79f。四个基础实现类确实位于运行包。
- 实际元库4.1成功；logs/w03-app-start-01.log、w03-app-start-02.log，两次启动后4.1记录仍为1；当前专用PID1037276。关闭企业模式，显式开启基础迁移；原2.40成功及双集团业务库保持原状。
- 提交前正式门禁runId：2ed1040d-65b4-4cfe-a872-169b6827fb85。Java39、HMAC5、API4、业务数据库14、基础元数据10、回执11、完整包拒绝3、真实浏览器8均通过，错误/跳过0；三个故障控制按预期拒绝。初次新增开关用例被旧企业测试配置先拦截，改用明确的隔离参数到达目标检查点，未放宽断言。
- 新W03测试账号对任务元库、GA/GB读取3次均拒绝；受保护旧检出两处Vue SHA及本地AGENTS/Handler/login/配置SHA未变；本地无暂存/提交。新增合成库和所有失败现场保留。
- 文档关系静态检查：72份MD、745个本地链接、193张表、4个JSON示例、R13/A33/V10关联正确，0错误。补丁ENT-004登记，W03/追溯/配置/字段字典/导航同步本小功能边界。

整合后架构优化复查确认：先核对全部已有表、同声明生成DDL及验证、已成功版本后仍只读验证、迁移开关与安全开关分离、所有执行工具指纹和W03必需回执已落实。本范围无新阻断项。数据库TRIM检查处理首尾普通空格；制表符/Unicode空白等身份输入须在下一领域命令统一拒绝并增加实际测试，不能把当前DDL称为完整接口字段校验。JPA自动DDL隔离、树环/父链、CAS及epoch事务、认证来源/请求生命周期、资源权威归属和跨集团HTTP拒绝仍待后续小功能；业务A/P不升级为通过。

本文件与实现同一交付提交。提交后门禁绑定的实际HEAD、工具/源码/JAR摘要和PID在任务logs/delivery-gate.json及logs/delivery-<runId>/remote-checks.json，推送前由现有钩子再次核验；不把此处提交前runId当作后续提交的通行证。Git记录及最终回执是交付状态依据，未合入开发分支或发布生产。

## 历史迁移／当前校验分离的后续落实

2026-10-08，本文“未来4.2应分离”已由[迁移演进单元](schema-version-evolution.md)落实：4.1仍按冻结V41建表，当前启动验证器读取独立当前目标及保留表清单，不调用旧迁移verifySchema。历史DDL逐字节指纹保持，正式生产目标仍为原四表，无正式4.2。新增真实JPA版本Repository／切面／监听器演进夹具补充本文件旧测试适配器限制；104/26是当前门禁，本文39/11及PID/JAR是历史快照。企业业务就绪与资格／审计／身份仍待实施。
