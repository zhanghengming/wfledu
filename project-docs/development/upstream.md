# 上游同步与补丁登记

`upstream` 为 GitHub 官方仓库；当前 GitHub Git 连接超时，`gitee` 是 FIT2CLOUD 备用读取源。任何备用源标签必须核对展开后的提交 SHA 与官方标签一致。基线版本和 SHA 记录在 [`baseline.json`](../phase0/baseline.json)。

交付远端以源码树快照为初始提交，未保留上游 Git 祖先关系，因为旧历史中的敏感凭据被 GitHub Push Protection 拦截。不要对远端快照分支直接执行上游 `git merge` 或假设共同祖先存在。完整历史保留在本地 `codex/phase0-upstream-history`；长期保管上游历史应另选受控镜像，但不得将被拦截的秘密推送到项目远端。

上游升级流程：读取新正式版 Release 与安全公告 → 获取标签并核对提交 → 将新旧官方版本树做差异比较 → 比对迁移、API、前端路由和依赖 → 在项目快照分支逐项移植并重构变更 → 跑构建、双租户、权限、嵌入和迁移测试 → 预生产演练 → 发布。不得直接以 `dev-v3` 代替正式标签。

每个改动官方 Core 的补丁在下表新增一行。优先通过公开契约扩展；必须改核心时，把调用方、风险和回归用例记录完整。

| 编号 | 上游文件/入口 | 变更原因 | 扩展替代方案 | 升级冲突风险 | 回归用例 | 负责人 |
| --- | --- | --- | --- | --- | --- | --- |
| ENT-001（W02/T01） | core-backend新增enterprise/bootstrap；core-backend/pom.xml增加enterprise-tests Profile | 企业必需安全服务缺失、歧义或社区替补共存时，在普通Bean初始化前拒绝启动；默认社区模式保持兼容 | 复用公开LoginApi/ResourceAuthApi/RowPermissionsApi/ColumnPermissionsApi；以Spring启动门禁扩展，不修改原查询和登录接口 | 低到中：上游认证/权限API或替补类变化需复核；测试Profile同时明确启用standalone | EnterpriseAssemblyGuardTest；完整应用拒绝启动；见[实现与验证](enterprise-bootstrap.md) | 本项目 |
| ENT-002（W02/T01） | SDK common新增enterprise/context；api-permissions新增AccessContextResolver；core启动门禁追加必需类型及企业测试 | 提供不可变集团/用户/版本事实和显式线程作用域；缺上下文拒绝；仅旧权限API齐备不能代替集团身份能力 | 新增共享基础/认证适配契约，不改原HTTP/查询接口，不注册全局过滤器；W03实现可信解析和接入 | 中：共享契约及后续请求/任务生命周期需独立评审，不能从客户端构造身份；嵌入上限另须明确携带 | AccessContextHolderTest；解析器缺失/名称伪造拒绝；完整包SDK字节核对；见[实现与验证](enterprise-context.md) | 本项目 |

`de-xpack` 不参与自研实现与公开提交。涉及 SDK 契约变更时，核对社区替补实现、现有 XPack API 契约及前端调用，但不访问或复制专有实现。
