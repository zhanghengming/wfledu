# W03/T02：企业JPA自动DDL隔离

2026-10-08。依据ADR-015、W03及R01/R03/R07/R12。本轮开始时仅完成基础迁移和校验整改；本记录的设计不是测试通过声明。集团/学校正式实体、领域事务、身份及企业HTTP接口不在本轮范围。

## 开始核对

- 唯一开发主目录：远程 `/home/data_dev_zhm/dataease-phase1-test/w02-security/source`；分支 `codex/phase1-security-baseline`，基准 `ab72c3b2cb2c126885b587ffe19caf0c232329d8`。本地辅助编辑逐文件核对后同步。
- 已读AGENTS、工作包/迁移skills、导航、W03/ADR-015、远程/开发/构建/测试/配置/数据库/迁移规范、W02、计划及相关字典/模型；未发现相关局部AGENTS。保留两端既有文档、AGENTS、前端生成差异及未跟踪输入，不读取XPack实现。
- 已实时核对任务源码、空索引和专用13306/16379/18100监听，当前任务应用PID1322264。旧3306/6379/8100、旧源码、服务和业务库均不操作。
- 本轮仅使用获授权 `de_phase1_w03_*` 新增合成库和现有最小权限测试账号；保留现场，不DROP。仅重启专用18100应用进程；不升级依赖或生产部署。

## 设计与架构自评

实际版本为Hibernate 6.6.53.Final。沿Spring Boot的HibernatePropertiesCustomizer注册SchemaFilterProvider和Integrator：企业保留前缀 `de_ent_` 的表及序列排除自动schema操作，社区对象仍沿原配置处理。不依赖企业开关关闭后移除过滤，避免未来实体在社区模式被自动建表。

映射检查在SessionFactory构造期间、自动schema操作之前执行：企业映射要求基础迁移开关开启；禁止显式catalog/schema、跨企业/社区外键及混合主/次表映射，禁止数据库标识生成器。检查过滤实例没有被其他配置替换。配置已有同类provider时明确拒绝，不静默覆盖其职责。未来需要共存时先设计组合契约及回归。

此过滤处理Hibernate受控schema链，不拦截任意JDBC/native SQL；正式DDL仍走SqlBlock，精确结构验收仍由FoundationSchema负责。过滤不授予数据权限，不代替企业请求就绪控制。不新增正式实体、Repository、HTTP或4.2迁移，不改变4.1 DDL。

依据：[Spring Boot扩展接口](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/orm/jpa/package-summary.html)、[Hibernate schema设置](https://docs.jboss.org/hibernate/orm/6.6/javadocs/org/hibernate/cfg/SchemaToolingSettings.html)、[Hibernate构造顺序](https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/internal/SessionFactoryImpl.java)。公开文档只支持方案调查，当前二进制及真实MySQL测试才证明本轮实现。

## 验证计划

使用Boot真实Hibernate自动配置及受控测试实体，非H2、非模拟schema工具：缺企业表不创建，已有企业结构/行不改变，结构偏差不被自动修复，社区建表/增列仍发生，正式迁移后可JPA读取/事务更新；拒绝关闭迁移开关、生成器、跨边界外键/次表、显式catalog/schema和配置替换。命名用例纳入正式门禁，保留原校验及桌面/移动故障回归。

完成局部测试后整体构建SDK、前端distributed、后端standalone，运行对应完整包，再执行正式verify-delivery入口。检查真实XML用例、结果和源码/工具/JAR/进程/runId一致性。失败先修复重验，实际证据在完成后追加。

用户/团队独立评审仍待记录，本轮架构自评不代替独立评审，不合入开发分支或开放未完成企业服务。

## 实际交付与架构复查

本轮隔离前置已实现并完成整合验证；不是W03整体或业务A/P通过。

| 项目 | 2026-10-08实际证据 |
| --- | --- |
| 源码 | 基准ab72c3b；三份生产实现、一份新测试、既有测试夹具两处可见性及两个门禁工具；最终提交由Git和最终回执记录 |
| 局部/正式Java | 60项（12/10/6/18/14），失败/错误/跳过0；logs/w03-jpa-tests-03.log及本轮正式unit.log |
| 命名证据 | 原8个结构回归和14个JPA回归由实际XML及回执逐项检查 |
| 门禁自身 | 17项通过，旧46项、缺JPA用例或回执拒绝 |
| 整体构建 | SDK/前端distributed/后端standalone分别退出0，logs/w03-jpa-*-build.log |
| 完整运行包 | SHA256 7a1b4c01fe9cc8700d034feb50d24265543b945721b0a7de93fa9414db69dd9e，四个新增生产class与编译输出逐字节相同 |
| 实际运行 | 专用18100/PID1337941，logs/w03-jpa-app-start.log启动成功；企业模式仍false，基础迁移true |
| 提交前正式门禁 | e7aa045b-c1db-4ae7-ae48-0c067b6e8013：Java60、HMAC5、API4、业务库14、基础元信息10、回执17、完整包拒绝3通过 |
| 页面与检测能力 | 8项桌面/移动真实浏览器通过，API/页面/资源/网络错误0；404/遮罩/移动提交3类故障均命中指定失败 |

未保护的对照用例使用同一Boot/Hibernate构造在另一新增合成库实际创建企业表，保护后的用例证明未创建；不是仅断言过滤函数返回false。已有四表SHOW CREATE TABLE及合成行在Hibernate更新前后相同，社区列实际新增；偏差保留且严格验证拒绝。正式迁移后JPA读取和事务更新成立，但此DML探针不验证正式领域CAS、epoch、Repository非空合并或身份资格。

初轮测试暴露了测试构造误用Table构造参数和次表拒绝先触发外键检查；第一次调整循环位置未生效，第二轮仍准确失败。修正后全量及正式门禁才通过，没有放宽断言或用失败结果交付；初轮日志保留为w03-jpa-tests-01/02.log。

架构复查完成：逻辑仅在启动期遍历元信息，不增加请求期查询、线程上下文或平行ORM；保留前缀的比较只用于SQL对象名，不改业务身份字符串；配置冲突及非法映射在自动DDL前拒绝，过滤不掩盖结构漂移。正式4.1声明/DDL、社区实体、SqlBlock执行链、SDK/API和前端源码均未改变。保留社区自动更新能力，没有靠全局关闭ddl-auto解决企业问题。

限制：实际数据库/Hibernate版本为8.0.46/6.6.53.Final；真实schema执行只验证update，create/drop/validate/truncate覆盖的是SPI过滤返回值，未执行删表或其他数据库方言。SchemaFilter不是通用SQL防火墙；任意native SQL、导入脚本、独立持久化单元及后来覆盖provider的自定义器须另行约束/验证。新增自动配置和Hibernate升级必须重新执行真实Boot回归，不能沿用本回执。当前pre-push仅保护任务检出，未接入远端CI。

用户/团队独立评审仍待记录。下一W03单元按字典映射正式四表和领域事务，验证学校权威映射、组织树、CAS、显式清空及epoch；真实企业HTTP开放前仍须就绪控制。历史版本与当前目标分离在新增4.2前落实，不改旧DDL；集团权限及嵌入仍按W04–W07推进。

提交后必须绑定新HEAD复跑正式门禁再推送，实际状态见任务logs/delivery-gate.json及对应delivery目录。合入与生产发布未执行；旧服务/库/源码和原有未提交输入保留。
