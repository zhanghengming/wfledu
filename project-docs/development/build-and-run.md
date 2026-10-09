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


## 共享开发服务器的受限构建补充（2026-10-09）

在124.221.139.87专用任务检出，先核对W02和[恢复记录](resource-safe-recovery.md)的进程／配置／容量，按已授权生命周期暂停18100／18120；必要的测试MySQL缓存释放只限已核对的13306实例，清洁重启前后比较数据和配置，不复用历史硬编码PID或操作原3306。保留全部合成库、旧服务、原源码及用户修改。

```bash
python3 -B tools/phase1/verify-resource-job.py --prebuild
python3 -B tools/phase1/build-safe.py
```

第二条要求第一条的新报告通过、两个任务应用已停止且源分支正确；按SDK install、完整distributed受限前端及build:flush、后端standalone,enterprise-tests顺序运行，保存独立阶段状态和源码／JAR摘要。后端显式启用真实测试，不用默认跳过测试的package结果代替。容量不足明确拒绝，共享服务器不绕过此入口原样执行无整组限制的8GiB脚本。

前端受限脚本经vite.bounded.config.ts复用原配置并限制文件并发16、CSS线程1；原脚本保留给其他适配环境。固定前端MemoryHigh=7680MiB、MemoryMax=8192MiB、老年代7168MiB，启动要求10240MiB；Java作业3584／4096MiB及6144MiB门槛，CPUQuota=200%，LimitCORE=0。全组取消／超时清理、互斥与主机余量保护见恢复记录，限额不自动增加。

完整包成功后依据登记的私有参数恢复18100／18120，验证同包／独立配置／4.7迁移，再按[测试规范](testing.md)及[交付后验收](post-delivery-acceptance.md)执行用户流程和完整门禁。构建、运行、最终HEAD及交付后复验分别留证；不用旧成功报告替代本轮执行。
