# v3.1.0 社区源码初步审计

审计范围：公开仓库提交 `5ce2c3e5d2c99c5d2e8ab8aed2b0fa622f4da594` 的 `sdk/`、`core/`、`installer/`。`de-xpack` 未初始化；下面仅列已经在公开源码中核对的事实和下一步需验证的入口，不代表完成逐表/逐 API 安全审计。

| 主题 | 已核对入口 | 发现与后续动作 |
| --- | --- | --- |
| 构建 | [`pom.xml`](../../pom.xml)、[`core/pom.xml`](../../core/pom.xml)、[`sdk/api/pom.xml`](../../sdk/api/pom.xml) | Java 21；根聚合 SDK，Core 单独构建；API 契约可考虑独立企业模块 |
| 本地登录 | [`SubstituleLoginServer.java`](../../core/core-backend/src/main/java/io/dataease/substitute/permissions/login/SubstituleLoginServer.java) | 社区替补登录仅允许 admin；多用户/OIDC 需完整身份实现 |
| 权限 | [`PermissionManage.java`](../../core/core-backend/src/main/java/io/dataease/dataset/manage/PermissionManage.java)、[`CorePermissionManage.java`](../../core/core-backend/src/main/java/io/dataease/system/manage/CorePermissionManage.java) | 行列权限 API 可选注入；企业模式需消除缺失时放行 |
| 图表取数 | [`ChartDataManage.java`](../../core/core-backend/src/main/java/io/dataease/chart/manage/ChartDataManage.java) | 查询同时涉及资源、行列、筛选、Provider；数据权限不可只放在页面入口 |
| 存储 | [`JpaUpdateNonNullAspect.java`](../../core/core-backend/src/main/java/io/dataease/config/JpaUpdateNonNullAspect.java) | Repository save 非空合并；租户字段和显式清空需单独验证 |
| 迁移 | [`InitSqlListener.java`](../../core/core-backend/src/main/java/io/dataease/listener/InitSqlListener.java) | `SqlBlock` 分组 1/2/3；失败可重试；企业版本组需验证执行顺序 |
| 缓存 | [`RedisCacheImpl.java`](../../sdk/common/src/main/java/io/dataease/cache/impl/RedisCacheImpl.java)、[`DefaultCacheImpl.java`](../../sdk/common/src/main/java/io/dataease/cache/impl/DefaultCacheImpl.java) | JCache/Redis 两种实现，TTL 语义不同；结果缓存键需租户化 |
| 分享/导出 | [`ShareTicketManage.java`](../../core/core-backend/src/main/java/io/dataease/share/manage/ShareTicketManage.java)、[`ExportCenterManage.java`](../../core/core-backend/src/main/java/io/dataease/exportCenter/manage/ExportCenterManage.java) | 票据、任务和文件下载均为跨租户高风险入口 |
| 异步 | [`ScheduleManager.java`](../../core/core-backend/src/main/java/io/dataease/job/schedule/ScheduleManager.java)、[`CommunityUtils.java`](../../sdk/common/src/main/java/io/dataease/utils/CommunityUtils.java) | Quartz 与 ThreadLocal 需显式传递/清理租户上下文 |
| 原生查询 | [`CoreDatasetGroupRepository.java`](../../core/core-backend/src/main/java/io/dataease/dataset/dao/auto/mapper/CoreDatasetGroupRepository.java)、[`DatabaseTimeManage.java`](../../core/core-backend/src/main/java/io/dataease/datasource/manage/DatabaseTimeManage.java) | 已发现 `nativeQuery`/`createNativeQuery`，POC 前扩展到所有字符串构造 SQL 路径 |
| 前端 | [`package.json`](../../core/core-frontend/package.json)、[`pagesConfig.ts`](../../core/core-frontend/config/pagesConfig.ts) | Vue 3 多页面及嵌入库，租户切换需核对主应用/panel/mobile/lib 状态 |

## 完整审计待办

1. 从 JPA Entity 和实际数据库导出完整表、索引、外键清单；给每张表标注“租户/全局/混合”和归属迁移方案。
2. 枚举所有 Repository、QueryDSL、Native SQL、JDBC、动态 SQL 入口，输出代码位置与租户过滤证明。
3. 枚举缓存键、文件路径、导出任务、Quartz JobData、异步线程和分享链接的上下文传播。
4. 绘制每个看板查询入口到数据源的实际调用链，并核对预览、图表、导出、嵌入一致性。
5. 用运行中的社区版验证配置 Profile、登录、迁移和数据模型；目前因工具链缺失未执行。
