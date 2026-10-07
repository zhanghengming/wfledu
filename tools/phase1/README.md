# 首期隔离测试夹具与验证入口

这些工具只用于已经批准的新任务环境，不是生产迁移或发布脚本。需要Java21、MySQL8、Node及当前前端已安装依赖；不安装工具或读取服务器已有应用配置。

目录布局：任务根目录下source、runtime、logs。私有客户端配置在runtime/conf，root只使用该任务runtime/mysql/mysql.sock；集团账号只连接127.0.0.1:13306。配置及凭据不入Git。

## 一次性业务夹具

[business-fixture.sql](business-fixture.sql)仅为新de_phase1_ga/de_phase1_gb创建学校维表、财务/教学事实表及合成数据。不是应用SqlBlock；不为真实业务数据定义会计口径。两集团各两学校，业务学校全局编号不同，同集团共表，金额与人数能人工核算。

执行前确认新实例端口13306、两个数据库及只读账号已按批准方案建立、表尚不存在，连接配置只指向任务socket。初次执行示意（从source根目录）：

```bash
mysql --defaults-file=../runtime/conf/root-client.cnf --batch --skip-column-names -e 'SELECT @@port;'
# 上一行必须为13306，且上述新库为空；不能改成全机默认mysql连接。
mysql --defaults-file=../runtime/conf/root-client.cnf < tools/phase1/business-fixture.sql
```

SQL遇到已有表会失败，不通过IF NOT EXISTS掩盖不匹配结构；MySQL DDL隐式提交，失败可能留下部分表。保留现场核对后恢复，禁止盲目重放、DROP或使用现有客户库。固定正数ID仅为合成样例，生产资源仍按项目Snowflake规则。正常业务写入与看板查询账号分离。

## 可重复的检查

```bash
python3 tools/phase1/verify-database-boundary.py
node tools/phase1/community-compatibility.cjs
```

[数据库检查](verify-database-boundary.py)先校验任务配置路径、socket/账号及实际13306，核对两集团本库汇总、双向跨库读/联表/元库读/本库写拒绝、字段注释及CHECK、上游初始化版本2.40成功。负向写操作在事务内回滚，异常关闭连接也回滚，不执行DDL。此入口要求专用合成夹具和基线元库初始化完成，不是任意存量库通用校验器。

[社区兼容检查](community-compatibility.cjs)核对app.pid对应本任务完整JAR，使用18100及源码真实/de2api前缀。验证主页、公钥接口、加密admin登录成功和错误密码拒绝；密码来自runtime/conf的私有文件，Token只在内存检查，不输出或存报告。该检查使用关闭企业模式的独立测试进程，不能用于证明企业认证已实现。

实际结果分别写任务logs下database-boundary-results.json、community-api-results.json。这些是运行证据，不是新增文档格式或提交产物。任何失败保持失败状态，不能通过跳过、空结果或捕获后返回成功消除。

## 登录初始化回归

```bash
node tools/phase1/login-startup-regression.cjs
```

[HMAC回归](login-startup-regression.cjs)通过已有esbuild编译实际前端模块，使用合成能力响应及密钥核对5类行为：明确社区跳过不存在的配置；扩展true/false、业务失败和HTTP失败均保留配置读取及正确签名。它不连接真实扩展服务，不加载私有配置，不安装依赖。

真实桌面/移动登录及刷新另由浏览器验证，详情见[404根因与修复](../../project-docs/planning/w02-login-404.md)。首页200或登录接口成功不能代替页面初始化、加载遮罩和表单可用性检查。

## 正式登录与交付门禁

```powershell
./tools/phase1/verify-delivery.ps1 -PlaywrightModulePath '<现有Playwright模块绝对路径>'
```

[门禁说明](../../project-docs/development/login-regression.md)是本工具流程的主出处。[浏览器回归](browser-login-regression.cjs)直接操作实际表单，桌面/移动8项及3类故障控制；[远程上下文/检查器](login-test-context.py)核对专用进程、产品源码/JAR和实际页面资源、真实单测及接口/数据库/企业拒绝结果；[10项回执拒绝测试](test-delivery-gate.py)防止空、旧、失败或缺项结果放行。新一轮开始即使旧成功回执失效，失败或未完成时不能复用旧结果。

只使用已有Chrome/Playwright模块，不自动安装、构建、部署、改剪贴板或重新启动服务。仅取专用admin凭据并送stdin，不保存密码/Token或完整私有配置。退出非零即失败，故障报告不当作正常通过。结果留唯一output/playwright/delivery/runId和远程logs/delivery-runId，最终门禁回执为logs/delivery-gate.json，生成证据不提交。

[pre-push模板](pre-push-check.sh)安装在已核对无既有钩子的远程任务检出，阻止源码/测试工具变更在没有当前合格回执时推送；不会替代远端CI或人工合并评审，不覆盖既有钩子或修改全局Git配置。
