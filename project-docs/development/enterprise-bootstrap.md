# 企业模式启动门禁：实现与验证

2026-10-07，W02/T01/P01装配子项。社区基准提交`d242674959e1bd99ed7d4d67d9ecd8f608a2d60b`。本文件仅说明本次已实现的门禁，不代表多租户、会话认证或完整P01已完成。

## 行为与架构评审

实现：[EnterpriseSecurityConfiguration](../../core/core-backend/src/main/java/io/dataease/enterprise/bootstrap/EnterpriseSecurityConfiguration.java)、[EnterpriseAssemblyGuard](../../core/core-backend/src/main/java/io/dataease/enterprise/bootstrap/EnterpriseAssemblyGuard.java)。复用公开SDK API，不读取或复制XPack实现；不改变现有HTTP路径和响应。

`enterprise.enabled`缺省或为`false`时保持社区装配行为。显式值仅接受`true`/`false`，忽略大小写和两端空白；空值或拼写错误拒绝启动。

开启时，LoginApi、ResourceAuthApi、RowPermissionsApi、ColumnPermissionsApi各要求唯一实现；任何缺失或歧义、社区替补权限服务/登录配置/登录数据共存都会拒绝。首期不开放任意行列策略；行列API是现有查询链的适配要求。

采用静态Bean方法与PriorityOrdered BeanFactoryPostProcessor，在普通业务Bean初始化前核对类型和定义。静态方法返回具体门禁类型，使Spring在后处理器排序时识别优先级。类型检查禁止初始化FactoryBean，不调用getBean创建权限服务。验证保留默认社区行为并覆盖名称伪造、缺失、歧义及副作用初始化顺序。

评审结论：该方案改动局限于新增核心分包和测试Profile，无新增持久化/对外接口。它只证明装配，不证明实现质量或旧CorePermissionManage、管理员分支和学校过滤已经适配；这些入口必须在后续工作包验证。没有生产假实现供门禁放行。此轮已完成架构自评，另一位评审者的拒绝路径复核待完成，未自动合入开发分支。

## 实际验证

远程唯一开发主目录：`/home/data_dev_zhm/dataease-phase1-test/w02-security/source`。Java21进程配置、Maven3.9.16、Node23.11.0、npm10.9.2；独立依赖副本，不修改现有源码、服务或数据库。任务输入文档来自当前协作工作区；需求和流程历史文档的既有未提交变更不夹带本次功能提交。

命令中的`<task>`为上述source父目录。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21
export PATH="$JAVA_HOME/bin:$PATH"
mvn -Dmaven.repo.local=<task>/m2 install -DskipTests
# core/core-frontend中执行，npm缓存位于本任务
npm run build:distributed
# 返回源码根目录
mvn -Dmaven.repo.local=<task>/m2 -f core/pom.xml -N install
mvn -Dmaven.repo.local=<task>/m2 -f core/core-backend/pom.xml test -Pstandalone,enterprise-tests
mvn -Dmaven.repo.local=<task>/m2 -f core/core-backend/pom.xml package -Pstandalone
```

测试：[EnterpriseAssemblyGuardTest](../../core/core-backend/src/test/java/io/dataease/enterprise/bootstrap/EnterpriseAssemblyGuardTest.java)。Surefire3.2.5只在enterprise-tests Profile启用，实际effective POM中skip和skipTests均为false；普通打包沿上游跳过测试。该Profile只运行enterprise目录的测试，不是全后端回归。

| 检查 | 实际结果 |
| --- | --- |
| SDK、完整前端（含flush）、完整后端包 | 全部成功；构建不等于行为测试 |
| 首次门禁测试 | 11项、1项失败：夹具的loginServer名称使替补条件不成立，未触发目标场景 |
| 修正后测试 | 11项、0失败、0错误、0跳过；修正为有类型但名称不同的认证适配器，真实触发旧名称条件回退 |
| 完整JAR，enterprise.enabled=true，缺必需实现 | 退出1，命中Enterprise security assembly rejected及社区替补拒绝 |
| 同一JAR，enterprise.enabled=tru | 退出1，命中明确开关配置错误 |

运行拒绝用例使用新`runtime/gate-home`、独立user.home和完整外置配置；数据库指向未启动的13306，Redis指向未启动的16379，ddl-auto=none。没有出现Hikari/JPA初始化或Web启动日志，18100没有监听。旧3306、6379、8100和两处Vue修改均保留。

本次JAR：`core/core-backend/target/CoreApplication.jar`；SHA256：`ca70f4ccb7c2276420f9cd653ee129df35958d0d09544715dbce41985d9fc57e`。日志位于任务根目录`logs`，包括sdk-build.log、frontend-build.log、effective-enterprise-pom.xml、enterprise-tests.log、enterprise-tests-rerun.log、backend-package.log、full-jar-missing-services.log、full-jar-invalid-switch.log、source-manifest.sha256。Surefire报告在后端target/surefire-reports；生成物不进Git。

未验证：企业实际会话/可信集团上下文、双集团和学校业务授权、迁移、浏览器/React宿主、查询/导出/缓存/任务旧入口。未创建新MySQL/Redis常驻实例、未执行DDL、未部署或重启现有服务。普通社区模式兼容只完成轻量上下文测试；完整正常启动回归等待隔离环境许可及建立。
