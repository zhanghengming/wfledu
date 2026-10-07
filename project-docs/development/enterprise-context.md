# 企业访问上下文基础：实现与验证

2026-10-07，W02/T01的上下文基础子项。此实现提供服务端不可变事实和显式线程生命周期，不提供HTTP认证、角色授权或集团切换接口。

## 实现与架构评审

共享类型：SDK common的[AccessContext](../../sdk/common/src/main/java/io/dataease/enterprise/context/AccessContext.java)和[AccessContextHolder](../../sdk/common/src/main/java/io/dataease/enterprise/context/AccessContextHolder.java)。仅保存正数tenantId、userId、accessEpoch、identityEpoch；不是对外DTO，不作为客户端身份声明反序列化。不保存角色、学校范围、admin或业务数据权限快照。

公开认证适配契约：SDK api-permissions的[AccessContextResolver](../../sdk/api/api-permissions/src/main/java/io/dataease/api/permissions/enterprise/AccessContextResolver.java).resolveAuthenticated(HttpServletRequest)。请求只作为可信会话传递载体；解析器必须验证用户、集团及当前安全版本，并验证普通用户的集团成员资格或平台管理员的专属逐集团访问资格，不能直接采信请求中的集团、用户、角色。缺失、过期或多重身份拒绝，返回上下文不授予资源或数据权限；当前没有注册其生产实现。

构造函数只检查字段形状。认证适配器须根据已验证会话、有效身份/集团及当前版本创建；消费者读取，不能从请求参数创建身份。异步捕获后仍须在执行和交付时重新校验身份、集团及当前授权，版本字段不是自动撤权机制。

```java
// context必须来自后续认证适配器；此示例不展示身份解析或授权判断。
try (AccessContextHolder.Scope ignored = AccessContextHolder.open(context)) {
    AccessContext current = AccessContextHolder.requireCurrent();
    // 根据current重新核验资源、操作、学校与当前安全版本，再执行。
}
```

使用普通ThreadLocal，不自动继承给子线程；缺上下文抛项目既有DEException/20001。open拒绝空值和已绑定线程上的重入替换；close只允许所属线程执行，重复关闭不影响后续作用域。异常也必须经try-with-resources清理，禁止手工吞掉清理异常。

架构优化：值和生命周期放SDK common，认证契约放api-permissions，保持单向依赖；没有Repository或全局请求过滤器。追加解析器为企业装配门禁必需类型，防止仅旧登录/权限API齐备就通过缺集团身份能力的装配检查。暂不猜测匿名/登录白名单；实际解析与请求生命周期适配随W03实现。这里的单测不证明浏览器伪造身份被接口拒绝，也不证明现有查询强制调用了requireCurrent。

四字段事实也不包含嵌入App/Session引用及资源动作上限；W07/W09必须明确携带并校验这些边界，不能用本记录替代受限会话或完整授权事实。所有授权仍须资源/操作/学校和应用上限一致求值。

已完成架构自评；用户已指定自己或团队进行共享契约的独立拒绝路径评审，结果仍待记录，未自动合入开发分支。上游升级需检查共享异常编码、Java版本和后续认证生命周期适配；没有复制XPack。

## 实际检查与限制

远程任务树：`/home/data_dev_zhm/dataease-phase1-test/w02-security/source`；Java21、独立m2/npm缓存及node_modules副本。使用既有enterprise-tests Profile，显式同时启用standalone。

| 检查 | 实际结果 |
| --- | --- |
| 最终轮SDK、完整前端、完整后端包 | 全部成功；resolver-sdk-build.log、resolver-frontend-build.log、resolver-backend-package.log |
| [上下文测试](../../core/core-backend/src/test/java/io/dataease/enterprise/context/AccessContextHolderTest.java) | 10项；缺上下文/非法值、正常/异常关闭、重入、旧作用域、子线程不继承、线程复用、显式异步、跨线程关闭及并发集团 |
| 连同门禁累计测试 | 22项（10项上下文、12项门禁），0失败、0错误、0跳过；resolver-tests.log及Surefire报告 |
| 包内SDK验证 | 完整JAR中的common依赖与构建SDK字节完全一致；提取的包运行4项缺上下文/绑定/子线程隔离/关闭检查；resolver-packaged-context-results.log |

此轮完整JAR SHA256：`7594a01d9490aab07c5d8a9db9ff1b0936a64079348d222aefd26540b726bf7b`。新增解析契约已存在于完整包的api-permissions依赖。新包的4项社区接口、14项数据库边界和两项企业拒绝启动检查再次通过；真实HTTP身份解析仍未实现。运行及最终交付在任务记录据实登记，生成物不进Git。

尚未完成：真实HTTP身份解析、过滤器强制接入、session失效/多重身份、成员/学校权威事实、平台逐集团全读、角色配对与学校过滤、缓存/任务交付撤权。此基础组件不能当作W02/P01全部通过或可供企业用户使用的版本。默认社区模式的正常运行和企业模式拒绝门禁分别沿第一项及本轮完整包回归验证。
