# 构建与本地运行手册

## 工具链

Linux 构建机建议 JDK 21、Maven 3.9.x、Docker Compose。先运行 `node tools/phase0-env-check.mjs` 检查基础工具链。根 `pom.xml` 只聚合 SDK；`core/pom.xml` 聚合前后端。前端 Maven 插件固定 Node 23.11.0 和 npm 10.9.2；独立前端开发时使用接近该版本的工具链。构建机建议至少 16GB 内存。

## 基线验证顺序

1. `git status --short`，确认没有用户改动或意外生成物；核对 `git rev-parse HEAD` 与基线 SHA。
2. `java -version`、`mvn -version`、`docker compose version`；版本不符合时停止构建。
3. 在隔离测试环境执行 `docker compose --env-file deploy/dev/.env -f deploy/dev/compose.yaml up -d`。先复制 `.env.example` 为未跟踪的 `.env`，填写仅本地使用的随机密码。不要在生产机器执行此模板。
4. 在仓库根目录执行 `mvn -B -ntp install -DskipTests`，安装公开 SDK 契约；记录任何无法获取的依赖及来源。
5. 执行 `mvn -B -ntp -f core/pom.xml package -Pstandalone -DskipTests`；确认 `core/core-backend/target/CoreApplication.jar` 和前端产物。构建前阅读上游 `AGENTS.md` 对前端 `build:flush` 的副作用说明。
6. 以 [`application.local.example.yml`](../../deploy/dev/application.local.example.yml) 为模板准备未跟踪的外部开发配置；把 `.env` 的变量注入应用进程，设置可写的 `DE_DEV_SUBSTITULE_PATH`，仅连接本地隔离 MySQL/Redis。确认健康、登录、创建数据源/数据集/看板基础流程。源码启动可能自动执行数据库迁移，严禁连接真实业务库或生产库。
7. 保存命令、结果、耗时、制品 SHA256、数据库迁移版本和已验证 Profile 到验证报告。

不要默认 `mvn clean`；项目 Maven clean 可能删除前端依赖与生成文件。当前机器 Java 8、Maven 3.6.1 且无 Docker，所以上述第 3～6 步尚未执行。

## 本地基础服务

`deploy/dev/compose.yaml` 只负责 MySQL 和 Redis，不会安装或启动 DataEase。本地端口默认绑定 `127.0.0.1`。关闭服务使用 `docker compose ... down`，不要加 `-v`，除非明确要删除测试数据卷并已确认目标。
