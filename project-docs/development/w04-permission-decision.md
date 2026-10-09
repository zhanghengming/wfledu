# W04 权限决策、来源预览与基础撤权

日期2026-10-09。实施状态以[剩余步骤执行记录](w04-remaining-execution.md)、[工作包](../planning/w04-authorization.md)及最终回执为准。依据[权限矩阵](../requirements/permission-matrix.md)、[技术细化](../technical/w04-authorization-design.md)、[字段约束](../technical/api-field-constraints.md)、[一致性](../technical/ddd-consistency.md)。不改变首期业务范围。

## 源码与职责

SDK PermissionContract/PermissionManagementApi新增预览请求，Server只调用服务。PermissionDecision是纯领域对象，不依赖JPA、Spring或请求上下文。PermissionFactLoader在现有集团短事务内批量读取当前事实；PermissionDecisionService负责管理预览和真实受控入口共用决策。ResourceOwnershipService在策略允许后仍检查原生空画布及METADATA_ONLY策略。

继续复用同一个管理事务、JPA及4.6/4.7存储，不增加数据库表、迁移版本、ORM、消息设施或权限缓存，不修改Vue、图表SQL及XPack。

## 已实施接口的字段契约

`POST /de2api/api/enterprise/v1/permissions/preview`，JSON、正式Bearer凭据、当前集团及MANAGE_AUTHORIZATION；严格拒绝未知字段、重复字段、显式null、数字ID、尾随JSON、其他编码及超界请求。目标身份只作为事实参数。

| 请求字段 | 约束 |
| --- | --- |
| userId | 必填，现有正Long字符串；本集团成员或明确GROUP_READ_ALL资格的用户。未知、跨集团及停用全局用户拒绝；本集团停用成员没有普通业务权限 |
| policyKind | 必填；DATA_ACCESS或RESOURCE_ACTION，与资源类型匹配 |
| resourceType | DATASET、DASHBOARD、TEMPLATE、SCHOOL_COPY；检查实际原生节点与当前集团归属 |
| resourceId | 必填，正Long字符串；DATASET必须是CoreDatasetGroup的dataset节点，目录／未登记／缺原生节点拒绝 |
| action | VIEW、EXPORT、DRILL、EDIT；DATA_ACCESS不接受EDIT |
| expectedEpoch | 可省略；提供时为正Long字符串，必须等于当前集团accessEpoch，不跨修订组合 |

返回沿用ResultMessage。data包含tenantId、userId、identityEpoch、accessEpoch、resourceId、resourceVersion、resourceType、action、policyKind；ID及版本为字符串。platformViewQualified表明明确的平台逐集团查看资格；authorizationAllowed、allowedSchoolIds及reason是策略结果；sources逐条说明grantId、version、subjectType、subjectId、assignmentId、action、effect、matchedSchoolIds。无任职的来源assignmentId为null；NONE学校范围不解释为全量业务数据。只有管理预览返回来源，普通查看不暴露授权图谱。

executionReady在W04固定false，pendingChecks指出尚待完成的数据源／学校字段绑定或资源执行门禁。预览不返回SQL、样本、连接信息或画布配置，不隐式创建主体，不写成功变更审计。策略允许不开放真实取数、编辑、导出、下钻或旧路由。

## 算法与一致性

ROLE的每条允许／禁止先与该任职的有效学校求交；ORG必须直接成员且组织及依赖可用；USER规则为增补或匹配禁止。允许并集扣除禁止并集，未授权拒绝。EXPORT、DRILL学校集合与实际有效VIEW求交；资源EDIT要求实际有效VIEW。GROUP_READ_ALL仅为当前有效集团的VIEW例外，不依赖普通成员授权；其他操作仍要求本集团有效成员及单独操作允许，并受该操作禁止约束，不隐授其他操作或授权管理。平台资格可以满足VIEW前置条件，不能代替操作授权。

事实加载显式核对当前事务、所属实际EntityManager、当前集团和已持有的集团读／写锁；不接受开放视图中的其他EntityManager。事实加载与管理写使用同一集团读／写锁。全局目标身份在所属JPA事务重查，用户读锁覆盖身份修订。绑定不可变事实后不跨修订缓存；旧会话后续请求重新认证、解析集团上下文并求值。旧epoch或无当前上下文拒绝。并发事务允许完整旧事实先完成或完整新事实再执行，不能混合旧任职和新规则；请求中途撤权后的文件／异步交付保障仍归W09。

输入读取预算明确有界：组织成员／任职各1000，有效学校候选5000，相关主体3000、规则1000、关联学校10000；组织闭包每批500、最多128轮、拓扑10000。超界拒绝，不接受截断结果。来源输出每个动作最多1000条、展开学校引用最多10000个，超界整项拒绝且不返回部分来源。预算是首期安全界限，不是已完成生产容量压测；后续扩容需按查询次数和时延证据调整，不增加默认放行或未经验证的缓存。

## 管理变更保护

角色停用、任职范围更换、成员组织变更及组织可用性变化都可能间接改变管理能力。保护比较受影响用户的前后有效能力；没有MANAGE_AUTHORIZATION的操作者不能借组织／角色管理获得或移除这些能力。最后授权管理员不变量与变更、集团epoch及审计在同一事务。

组织依赖不仅是parentId树，还包含部门的显式schoolId。学校停用或祖先变化应沿两个关系追踪受影响部门及成员，合并重复依赖后再比较能力。schoolId仍是不可变归属；本步不增加移动学校或替换学校归属功能。

## 验证与限制

新增具名领域测试、真实MySQL/Tomcat接口回归、独立整包用户流程及回执拒绝测试，必须由当前门禁执行并保存新runId。第5步定向15项已执行无失败、无错误、无跳过；它不是最终整包或交付后验收。后续完整证据在最终回执记录。

W05才验证集团分库绑定、聚合前学校过滤和真实图表数据；W06模板副本与编辑；W07宿主嵌入；W08页面矩阵；W09导出、文件、缓存、异步任务及交付中撤权。学校副本在本步仅核对已有归属，不宣称实例化流程已完成。真实世外数据、宿主系统及用户／团队独立人工评审仍未验收。

## 同类引用与撤销整改（2026-10-09）

副本预览／UPSERT同时复核模板侧表和实际原生对象，模板缺失、删除、错误类型或跨集团时拒绝；DELETE不要求原生依赖恢复。成员DISABLED可以清理已停用全局用户的本集团关系，ACTIVE仍拒绝该身份。详见[同类缺陷整改](w04-defect-followup.md)，不因此开放W06副本编辑。
