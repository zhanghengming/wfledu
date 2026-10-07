# W02：社区登录页面404根因与修复

日期：2026-10-07，W02/T01的社区兼容修复；基准提交3650ce1。远程唯一开发主目录仍为`/home/data_dev_zhm/dataease-phase1-test/w02-security/source`，任务分支codex/phase1-security-baseline。原有目录、服务、数据库和本地两处Vue修改保留。

## 根因与复现

用户报告打开页面出现`Request failed with status code 404`。使用独立Chrome会话，通过新本地18110转发到远程18100复现；服务器首页、dekey及常用初始化接口返回200，并非SSH端口或API前缀错误。

实际404是`GET /de2api/setting/authentication/status`和`GET /de2api/perSetting/hmac/info`。社区运行包没有扩展认证配置实现，而桌面/移动登录组件和HMAC加载器仍无条件调用它们。桌面登录组件的认证状态请求失败后，初始化回调未执行，页面持续“加载中”，账号、密码和登录按钮被禁用；移动页面也保留加载状态。

本地既有Handler已有社区能力识别修正；本轮专用检出从干净基准创建，未包含该未提交修正。原四项接口检查只覆盖主页、公钥和登录HTTP行为，没有覆盖浏览器初始化，故未发现此缺口。favicon缺失是另一个静态资源请求，与认证状态导致的Axios错误及初始化阻塞分别记录。

## 最小修复与架构复查

- [桌面Handler](../../core/core-frontend/src/views/component/login/Handler.vue)和[移动Handler](../../core/core-frontend/src/views/component/login/MobileHandler.vue)：先查询已有`/xpackModel`。仅成功且明确返回`data=null`时按社区路径执行初始化回调，不请求不存在的扩展认证状态。
- [HmacTool](../../core/core-frontend/src/views/tools/HmacTool.ts)：使用相同的明确能力判断；仅确认社区模式时跳过扩展HMAC配置。`true`、`false`、失败响应或未知结果均不冒充“明确社区”，继续既有HMAC配置路径。
- [移动登录页面](../../core/core-frontend/src/views/mobile/login/index.vue)：加载遮罩与既有扩展组件使用相同能力条件，避免社区模式不挂载扩展组件却永久显示其遮罩；社区已有凭据与桌面一样交由服务端核验，扩展模式原有复杂度校验保留。
- 保持现有接口、响应包装、认证拦截器和SDK契约；未新增默认放行的认证Bean或伪造配置接口。企业启动门禁仍要求完整安全实现。

架构复查结论：能力探测与身份/授权分别承担职责；社区能力结果只控制可选扩展配置加载，不授予集团、学校、角色或资源权限。已有HMAC加载异常处理语义未在本补丁重构，企业认证、签名及权限闭环仍须后续真实入口验证。本地Handler原有修正仅核对，不整文件覆盖；其他本次源码按精确清单回传。

## 回归与交付

新增[HMAC回归工具](../../tools/phase1/login-startup-regression.cjs)，直接编译实际HmacTool并验证5类合成场景：明确社区无配置请求；扩展true/false、业务失败及HTTP失败均保留配置加载和正确签名。合成密钥不是真实凭据；此测试不代替真实扩展服务验证。

首轮夹具遗漏IV前缀，导致签名检查失败；修正为实际公开前端使用的IV与密文组合格式后，5项均通过。没有为了通过测试改动产品加解密算法。

首轮完整包已完成桌面真实表单登录及刷新，API错误为零；移动实测发现默认遮罩与条件组件不一致，进一步修正后重新整体构建。首轮包SHA为`edb8ccf0e87e3879c6915d83a790730c06f73c1405e11435c259589d9b1546e1`，只保留为中间证据，不作为最终验收包。浏览器CLI不支持读取私有测试文件，表单验证使用同一已缓存Playwright的独立Node驱动；临时凭据仅含合成管理员密码，放仓库外且限制ACL，不输出，验证后删除。

第二轮包SHA为`b701ecf1e0cbb4eea7bba3a1b0c93e02e2e7c671fa10fe11779d2d4763ee41f1`，桌面登录及刷新通过，移动初始化遮罩消失；移动表单仍停留登录页面且没有发出登录请求。执行实际旧密码校验函数确认其拒绝了兼容脚本和桌面已验证的社区凭据。原因是登录页面硬编码了创建密码的复杂度要求，社区后端没有该要求。仅调整社区登录的客户端预检，保留服务端密码核验及扩展模式行为；再次整体构建，第二轮移动失败记录保留，不改写为通过。

| 检查 | 本轮状态 |
| --- | --- |
| 定向ESLint | HMAC模块及移动登录页面通过，无自动修复；两个Handler被项目原有规则忽略，不能写成lint通过 |
| HMAC合成回归 | 最终源码5项通过；404-release-hmac-regression.log |
| SDK、完整前端、完整后端构建 | 全部成功；404-release-sdk-build.log、404-release-frontend-build.log、404-release-backend-package.log |
| 企业基础单测 | 新报告22项，0失败/0错误/0跳过；12项门禁和10项上下文，404-release-enterprise-tests.log及Surefire报告 |
| 新包社区API及数据库回归 | 4项API、14项数据库检查通过；404-release-community-api-results.json、404-release-database-boundary-results.json |
| 桌面/移动真实浏览器 | 两入口初始化、真实表单登录及刷新通过；API错误均为0，没有请求上述缺失扩展接口；本地output/playwright/404-browser-results.json |
| 企业完整包拒绝启动 | 缺安全服务和非法开关均退出1，命中各自拒绝原因，早于Hikari/JPA/Web启动；404-release-full-jar-missing-services.log、404-release-full-jar-invalid-switch.log |

最终包SHA256为`3a61faf707d55539ad3bfcbb445cd3de3d37ce849226a2f01924be00f863e538`，位于任务源码`core/core-backend/target/CoreApplication.jar`。仅停止已核对所有者及完整命令的任务进程，再以原命令启动最终包，PID652906；日志404-release-community-startup.log。最终源码清单为logs/404-release-source-manifest.sha256，包清单404-release-jar.sha256。浏览器验证使用已有Chrome和独立18110转发；用户18010转发未改动。

最终门禁复验的第一次命令漏传外置配置参数，在日志目录初始化阶段失败；没有记为企业门禁通过。保留404-release-gate-harness-missing-config.log，补回原有gate-home配置入口后两项门禁均按预期拒绝；不修改配置或产品启动代码。

当前修复不增加企业表或迁移，不处理前一轮评审中的数据库脚本assert及报告追溯整改；数据库复验使用`python3 -E`忽略环境优化选项。Git交付仅包含四个前端文件、回归工具和三份实现说明，最终提交在[安全基线追加记录](w02-security-baseline.md#9-社区登录404修复追加交付)登记；原记录作为历史快照保留。该修复经过架构自评，不冒充用户或团队的独立安全评审。

## 用户验收

使用既有SSH转发打开`http://127.0.0.1:18010/`并强制刷新，预期加载提示消失、输入框和登录按钮可用，无上述认证配置404弹窗。移动端对应`/mobile.html#/login`。登录凭据由专用私有配置安全取用，或由兼容脚本验证，不写入聊天、文档或Git。

这只是社区运行和W02基础的验收；校长/财务数据范围、权限页面和宿主免登录嵌入仍待开发，不能在此记录标为通过。背景及已有证据见[安全基线](w02-security-baseline.md)、[工具说明](../../tools/phase1/README.md)。

## 漏测整改

用户要求追查为何原测试未发现问题并落实预防。原API工具绕过浏览器初始化、移动表单及路由；此前测试缺少逐次桌面/移动整包基线，临时浏览器驱动也未进入正式工具。接口结果真实，但不能据此认定页面可用。优化规则及正式执行入口见[登录回归门禁](../development/login-regression.md)。

首个完整成功门禁runId为874d48d6-5c5b-446c-9a51-67489b91cc8a：桌面/移动8项、三类浏览器故障控制、9项报告拒绝测试及既有远程22/5/4/14/2项全部通过。第一次完整执行的浏览器及故障检测通过，但回执使用客户端时间导致过期拒绝，整体未通过；统一服务器时钟后重跑成功。失败记录保留，未把拒绝删掉或扩大TTL掩盖问题。

本轮只增加测试工具及规则，没有改产品源码、数据库、服务或应用包；继续运行本页已记录SHA的完整包。架构自评采用外置测试编排、不引入SDK反向依赖或产品测试接口；最终提交、当前HEAD重跑及推送钩子证据见[安全基线追加](w02-security-baseline.md#10-登录测试门禁补强)。默认门禁能阻止同类问题被标记通过，不承诺所有未来缺陷都不会出现。
