# ADR-002：模块化单体与扩展边界

状态：建议，待评审。日期：2026-09-29。

决定：首期维持 DataEase 的单体运行方式。企业能力按 tenant、iam、permission、embedding、audit、quota 分包；共享契约进入 SDK，应用实现进入 Core 或独立 Maven 子模块，前端沿用现有 Vue/Pinia/路由与组件体系。具体 Maven 位置由基线构建 POC 决定。

理由：避免将图表、数据集与身份链路拆成分布式事务；保留对现有 API、Provider 和前端嵌入库的兼容。`sdk` 不依赖 `core`，业务模块不直接跨模块调用对方 Repository。

影响：核心修改登记补丁；禁止建立一套并行的响应包装、ORM、权限注解或前端状态管理体系。
