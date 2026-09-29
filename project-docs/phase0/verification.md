# Phase 0 交付与验证状态

更新：2026-09-29。基线提交：`5ce2c3e5d2c99c5d2e8ab8aed2b0fa622f4da594`，官方发布标签 `v3.1.0`。GitHub Git 连接超时后，使用 FIT2CLOUD Gitee 同名附注标签获取源码；标签展开后的提交与 GitHub 标签指向的提交一致。`upstream` 仍指向 GitHub，`gitee` 仅作读取备用源。

交付仓库采用当前源码树快照作为首个远端提交，不包含上游旧提交历史。原因是目标 GitHub 仓库的 Push Protection 拦截了上游历史中的 Mapbox Secret Access Token；未绕过保护，也未把该历史提交推送到远端。完整上游历史仅保留在本地 `codex/phase0-upstream-history` 分支。

## 已落地

| 交付物 | 位置 | 状态 |
| --- | --- | --- |
| 正式版源码基线 | 远端快照分支 `codex/phase0-foundation`；上游 SHA 记录于 `baseline.json` | 已检出，未运行构建 |
| 公开源码初步审计与后续入口清单 | `project-docs/phase0/source-audit.md` | 初审完成，逐表/逐 API 审计待 POC 前完成 |
| 总体、模块边界、租户、权限、嵌入、部署和威胁设计 | `project-docs/architecture/` | 待业务与安全评审 |
| 全局决策记录 | `project-docs/adr/` | 待评审 |
| 编码、API、数据库、配置、迁移、测试、可观测性、Git 流程 | `project-docs/development/` | 待评审 |
| 项目协作约束 | 根目录 `AGENTS.md` 的“二开补充规范” | 已落地 |
| 本地基础服务和应用叠加配置模板 | `deploy/dev/compose.yaml`、`.env.example`、`application.local.example.yml` | 待 Docker 与应用环境验证 |
| 静态检查和 CI 基础门禁 | `tools/phase0-check.mjs`、`.github/workflows/phase0-checks.yml` | 本地静态检查可执行；远端流水线未运行 |
| 构建环境预检 | `tools/phase0-env-check.mjs` | 当前机器未通过，缺 Java 21、Maven 3.9.x、Docker Compose |
| 上游补丁登记模板 | `project-docs/development/upstream.md` | 已落地 |

## 当前环境事实

- Windows 工作区；`git 2.33.0`、Java `1.8.0_172`、Maven `3.6.1`、Node `25.8.0`、npm `11.11.0`。
- 未找到 Docker CLI，也未配置 WSL 发行版。
- `pom.xml` 要求 Java 21；前端 Maven 插件固定 Node `23.11.0`、npm `10.9.2`。当前 Java 和 Maven 不符合官方源码部署环境。
- 官方源码的 `de-xpack` 指针存在，但未初始化；不会将其作为可构建的社区源码。
- 根 Maven 只聚合 `sdk`；完整社区核心须另构建 `core`。后端 Surefire 配置了跳过测试，单纯 `mvn test` 成功不能视作测试通过。

## 未通过的验收门禁

1. **源码编译与运行**：缺少 Java 21、Maven 3.9.x、Docker/MySQL 测试环境；尚无前后端构建、镜像或运行结果。
2. **数据库迁移验证**：当前只有现有 `SqlBlock` 机制的源码审计；没有隔离数据库，未执行任何迁移。
3. **安全与租户隔离测试**：业务功能尚未实施，不能声称通过。
4. **CI 远端运行**：工作流已配置，尚未推送执行。

## 进入 POC 前的门禁

- 在隔离 Linux 或容器构建机准备 JDK 21、Maven 3.9.x、Docker Compose、MySQL 8、Redis，并确认依赖仓库可达。
- 先按 [构建与运行手册](../development/build-and-run.md) 完成社区版基线构建和登录冒烟测试；保存实际日志与制品校验值。
- 评审 [租户模型](../architecture/tenant.md) 的用户跨租户、平台管理员、资源归属和删除保留策略。
- 评审 [权限模型](../architecture/permission.md) 的行规则冲突语义及导出/分享/嵌入传播。
- 确认首个接入系统的 OIDC 提供方、业务数据源与嵌入宿主域名。

Phase 0 的设计与规范已可评审；“可运行基线”和“生产就绪”仍是后续待验证事项。
