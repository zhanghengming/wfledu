# W04第2步：角色、任职与间接提权保护

日期：2026-10-08。范围为[W04工作包](../planning/w04-authorization.md)第2步，设计依据[技术细化](../technical/w04-authorization-design.md)及[开发者复核](w04-design-review.md)。本文件说明实际实现和可复现验收；最终版本及实测结果以文末回执为准。W04全部完成仍须步骤3—7，不因本单元交付而关闭工作包。

## 实现边界

复用冻结4.3的角色、任职根、任职学校和类型主体；没有新增表、修改历史DDL或执行4.6／4.7迁移。沿用Java21、JPA、现有集团事务／认证／统一响应；SDK只包含契约，不依赖核心实现。不改Vue框架，不读取或复制de-xpack专有实现。

集团管理员可以用正式接口配置角色及成员在学校的任职。任职根固定“集团＋成员＋角色”，学校集合是该根的显式范围；同一人在A1任校长、A2任财务，返回两条配对任职，不能形成角色集合×学校集合。学校／数据集的业务授权及权限预览仍待W04后续；实际图表、导出、编辑与React嵌入分别待后续工作包。

## 实际公共契约

路径前缀 `/de2api/api/enterprise/v1/`，以下接口均POST JSON，需要正式Bearer会话、当前集团和有效MANAGE_ROLES。接口不接受tenantId或浏览器用户身份。所有ID／expectedVersion为正十进制字符串；分页为整数，默认1／20，上限10000／100；沿用统一PageResult，records保留任职配对。

| 接口 | 字段 | 行为 |
| --- | --- | --- |
| roles/page | pageNum、pageSize可省略 | 当前集团分页，按ID排序，返回id/version/code/name/status |
| roles/save | mode、code、name、status；UPDATE须id/expectedVersion | CREATE禁id/version字段；code为1—64 ASCII字母数字及下划线／短横线，首位字母数字；name沿现有Unicode校验、最多128码点；code创建后不可换 |
| assignments/page | 可选memberId、roleId、schoolId及分页 | 所有筛选引用先核对本集团；返回id/version/memberId/roleId/schoolIds/status |
| assignments/save | mode、memberId、roleId、schoolIds、status；UPDATE须id/expectedVersion | schoolIds为完整集合，最多500项，无null／重复；ACTIVE非空且成员、角色、用户、学校及祖先有效；DISABLED允许空集合；UPDATE不能换成员／角色 |

status仅ACTIVE／DISABLED。新DTO拒绝显式null、数字ID、未知／重复字段、尾随JSON；请求体64KiB、嵌套深度16。字段可省略与null不等价，CREATE出现id:null也拒绝。不改变既有社区接口的Jackson行为。

写响应为id、version、accessEpoch字符串。自然键重复50003、CAS冲突50002、非法组合10001、资源不存在或不属于当前集团70002、无权限70001、无会话20001。客户端检查业务码；错误data为空，响应Cache-Control为no-store。

## 事务与安全不变量

角色CREATE在相同事务登记ROLE主体，不默认授予管理或业务权限。任职CREATE先flush根再写学校，满足实际MySQL外键顺序；UPDATE用版本CAS和显式删除／插入替换集合，空DISABLED确实清空，避开Repository非空合并切面。

写请求复用用户→集团锁序，先核验会话及当前管理能力；角色／任职、版本、集团accessEpoch、审计同事务提交。失败回滚全部内容。任职列表的学校集合按一页根ID一次加载，避免逐根N+1；不调用外部数据源或占用元库事务运行导出。

新增ManagementPrivilegeGuard覆盖角色状态、任职、既有成员组织关系及组织状态／父链变化。变更前检查操作者MANAGE_AUTHORIZATION；没有该资格时，采集受影响用户当前七类有效管理能力，写后flush并清空持久化上下文，从本事务事实重新求值。任何有效管理能力变化均拒绝，不能用新获得的资格批准当前操作。已有该资格的操作者仍须通过最后有效授权管理员不变量。

组织影响集合从本集团拓扑寻找实际子树，按100项分块查询组织成员和学校任职所关联用户；不会借其他集团学校。只改变名称不扩大资格影响集合。计算不覆盖目标用户的请求ThreadLocal身份；异常仍由原finally清理。最后管理员保护在所有相关路径执行，而非仅保护个人授权行。

## Bug根因及本轮检出证据

旧实现只核对MANAGE_MEMBERS／MANAGE_ORGANIZATIONS，没有核对“改变关系后是否新增管理资格”。W03回归证明了普通成员不自动获管理权限、直接停用最后管理员和既有资格求值，却没有走“委托成员管理员把自己加入已获授权的组织”这个组合路径。

本轮先用旧运行包真实复现：期望70001，实际0；隔离区logs/w04-step2-old-bug.json保留22次HTTP及失败用例w04.member-indirect-escalation。修复后用相同路径重新验证，同时增加任职提权、移除组织禁止和角色／学校导致最后管理员失效。失败用例不能用只检查菜单、Controller注解或测试总数代替。

## 验收流程与命令

在本地工作区PowerShell运行：

```powershell
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes data_dev_zhm@124.221.139.87 'python3 -B -E /home/data_dev_zhm/dataease-phase1-test/w02-security/source/tools/phase1/verify-w04-roles.py'
& tools/phase1/verify-delivery.ps1 -PlaywrightModulePath 'C:/Users/95877/Documents/ChatGPT/dataease二开/output/playwright/npm-cache/_npx/31e32ef8478fbf80/node_modules/playwright'
```

第一条实际执行：平台登录→创建两集团及合成管理员→改密／选集团→建学校与角色→同人不同学校任职→配对回读→CAS／替换／清空→两方向跨集团拒绝→委托提权拒绝→最后管理员保护。每次建立新合成对象，保留旧对象；正式管理资格的授予用受控SQL合成夹具，资格配置接口仍未完成。真实登录、公开角色／任职、成员／组织接口及判定使用生产实现，不用Mock／公开测试绕过接口。

脚本检查专用PID／argv／进程启动时间／JAR、私有600配置、MySQL13306及合成库标记。报告logs/w04-step2-http.json只含用例状态、HEAD、JAR及管理进程摘要。完整门禁额外运行既有W03、真实Java/MySQL/Tomcat/OSIV、结构、分库、社区登录、桌面／移动浏览器及三种故障检出。18010仍是社区兼容入口，不能据页面菜单验收W04管理接口；管理UI属于W08。

本单元四个新增Java方法和48个具名关键HTTP用例纳入强制门禁；Java总门槛186，回执拒绝回归40，保留W03全部必需集合。漏用例、旧HEAD／JAR／进程／工具、失败或过期报告拒绝交付。交付后按[自动验收规范](post-delivery-acceptance.md)独立执行上述两条命令并另存新回执。

## 架构复核与限制

开发者已核对实际SDK→Server→Manage→JPA及旧成员／组织内核。安全不变量集中到同一个守卫，继续复用既有权限求值和最后管理员检查；不增加平行权限系统或宽松替补Bean。原组织内核的审计回调在持久化校验阶段执行守卫，失败仍由内核事务整体回滚。

管理资格比较目前每个受影响用户重新检查七类能力；组织拓扑及受影响用户规模较大时成本增长。本轮只证明正确性与合成环境流程，未测生产规模／P95，不承诺性能容量。后续优化应在同一事务构建不可变资格事实并批量求值，保留本轮所有拒绝用例；不能为了减少查询截断DENY或跳过受影响成员。

本轮未验证真实世外业务数据、宿主登录联调、业务策略、缓存／异步任务交付撤权、矩阵页面、图表／导出／编辑／嵌入。用户／团队的独立人工评审仍待记录，开发者架构复核不替代该评审。

## 本单元实测状态

远程SDK、完整前端distributed和后端standalone／enterprise-tests整包均成功；后端186项实际测试失败／错误／跳过为0。新JAR SHA256为2e2a8639b0807b608a27f5d8209df1a44abdbe9b6d2dcc1316f5f18e36ccdcf0；兼容18100 PID1520802、管理18120 PID1520967。

提交前完整门禁bd253dc8-17db-4f7c-ada6-4c87b7ad2dd1通过：W04实际HTTP71（必需48），W03实际HTTP140（必需48），回执拒绝40，结构22，分库14，社区4／HMAC5／装配拒绝3，真实桌面移动8及故障检出3。提交前HEAD为0d0edb789766d98d99974485242009bc6e821970，不能冒充最终新提交。

SDK／前端／后端日志分别为logs/w04-step2-full-sdk.log、w04-step2-full-frontend.log、w04-step2-full-backend.log；提交前证据归档logs/w04-step2-precommit-gate.json及对应delivery目录，旧Bug失败证据保留。原25项未提交修改内容、六份文档追加前字节和旧服务监听均核对保留；没有重启原服务。

源码提交654ccf82d428ca8a6bacc30131cc53207a0517a5已推送任务分支codex/phase1-security-baseline，远端SHA一致。新提交门禁ea65b81f-a131-480d-b445-6c5dac292ae0的全部上述测试与浏览器通过；最后只读身份核对时SSH中断，原脚本退出1。保留logs/w04-step2-postcommit-interruption.json，600秒内重新核对同一次全部原始证据及当前完整身份相同，补跑回执上传／严格verify-gate，退出0；不以中断脚本退出码冒充整条命令成功。通过回执归档logs/w04-step2-postcommit-gate.json。

文档提交fe1d0d7c796ca6f13a72aefe95c281faa633edca后独立用户流程71及完整门禁59fe00f0-3966-43c4-878b-f6dde1469e8c实际通过；整条门禁命令退出0，未复用前次回执。最后传输检查发现五份远程W04文档中文被Windows子进程CP936输出与工具UTF-8解码之间的转换损坏；本地原文正常，产品源码／工具／JAR不受影响。

修复采用ASCII转义JSON传输原文，逐文件比较规范化UTF-8摘要并拒绝U+FFFD替换字符。后续文档同步优先scp传原始文件字节或ASCII转义JSON，不将含中文的终端stdout作为文件内容；先记录目标原摘要，核对两端并只更新本轮自有文件。这个检查补入单元交付清单，不以Markdown链接通过代替中文内容完整性。

本次文档修复不改变产品；最终源码及passed状态核对logs/delivery-gate.json，并以output/w04/w04-step2-delivery.md与w04-step2-post-delivery.md记录为准。最终文档提交后重新执行用户流程及完整门禁，不能将上面fe1d0d7的报告当作新HEAD通过。
