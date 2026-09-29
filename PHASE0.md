# DataEase 二开 Phase 0

本仓库以 DataEase `v3.1.0` 正式发布提交 `5ce2c3e5d2c99c5d2e8ab8aed2b0fa622f4da594` 为基线。Phase 0 的目标是固定事实、设计边界和团队协作规则，供评审后实施第一阶段功能。

GitHub 远端分支采用当前源码树快照作为初始提交。上游旧历史中的敏感凭据触发了目标仓库的 Push Protection，因此远端快照不包含上游历史提交；基线提交 SHA、官方远端和版本信息仍保留用于溯源。完整历史仅保留在本地 `codex/phase0-upstream-history` 分支，后续升级按版本树差异评估。

文档入口：[交付与验证状态](project-docs/phase0/verification.md)、[源码初审](project-docs/phase0/source-audit.md)、[总体架构](project-docs/architecture/overview.md)、[模块边界](project-docs/architecture/module-boundaries.md)、[开发规范](project-docs/development/standards.md)、[架构决策](project-docs/adr/README.md)、[评审清单](project-docs/phase0/review-checklist.md)。

当前阶段没有实现多租户、OIDC 或嵌入业务能力。架构文件中的表、接口和配置项是待实施的设计契约；以验证状态文档区分已完成、待环境验证和待评审的事项。

官方源码的 `de-xpack` 是独立且未开源的子模块。本项目只使用社区版公开源码和公开契约，自研实现放在独立命名空间，不提交或复制该子模块的实现。
