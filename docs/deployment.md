# 腾讯云 / 宝塔部署记录

当前阶段：**W8-D1 现场盘点待完成，W8-D2 本地打包与持久化验证已完成，尚未连接腾讯云服务器。** 以下候选配置没有云端检查或部署成功的结论。

## 现状与待核对项

用户提供的规格为 4 核 CPU、4 GB 内存、40 GB 系统盘，使用宝塔 Linux 面板。规格不代表当前可用资源。本机没有 `~/.ssh/config`；暂未取得服务器连接方式、域名或现场检查结果。

| 项目 | 当前证据 | 部署前需要确认 |
| --- | --- | --- |
| 系统与架构 | 未核对 | 发行版 / 版本、x86_64 或 ARM、更新与维护方式 |
| CPU、内存、磁盘 | 仅知道购买规格 | 空闲内存、Swap、磁盘余量、现有服务资源占用 |
| 宝塔入口 | 用户确认使用宝塔 | Nginx / Apache、现有网站及站点目录、80/443 归属 |
| 数据库 | 未核对 | 已有 MySQL / MariaDB、Redis 的版本、用途、备份和访问限制 |
| 容器环境 | 未核对 | Docker / Compose 是否安装、是否已有业务容器、数据目录 |
| 端口 | 未核对 | 监听地址、已占用端口、腾讯云防火墙和系统防火墙规则 |
| 域名与 HTTPS | 未提供 | 拟用域名、DNS 指向、证书、宝塔站点配置 |
| 外部连接 | 仅开发机曾联调模型 / MCP | 云端到 DeepSeek、Tavily、SMTP、飞书的实际连通性 |
| 部署位置 | 未确定 | 可用目录、权限、持久化目录与备份位置 |

## 只读盘点

脚本：[scripts/server-preflight.sh](../scripts/server-preflight.sh)。它读取系统、内存、磁盘、监听端口、常见宝塔站点配置**文件名**、相关服务名、客户端版本及本机 Docker 容器摘要。不会读取站点配置正文、环境变量、数据库记录、证书或私钥；不会安装软件、重启服务、修改端口或防火墙，也不调用模型或发送通知。

Docker 查询固定使用 `/var/run/docker.sock`，不跟随可能指向其他主机的 Docker context / `DOCKER_HOST`。采用 rootless Docker 或自定义宝塔路径时，需要人工补充。常用工具缺失或权限不足会显示 `[UNAVAILABLE]` / `[FAILED]`，继续其余检查并退出码 2；不能把缺失工具理解为没有该服务。

在宝塔终端执行：将脚本内容保存到自己指定的临时文件，检查内容后运行 `bash server-preflight.sh`。不需要为了盘点安装软件或打开新端口。普通用户看不到的信息在面板中补充；脚本不自动使用 sudo。

若已配置 SSH，在本机项目根目录把 `YOUR_SSH_ALIAS` 替换为实际连接别名，执行：

```powershell
New-Item -ItemType Directory -Force .local | Out-Null
Get-Content -Raw scripts/server-preflight.sh | ssh YOUR_SSH_ALIAS 'bash -s' > .local/server-preflight.txt
$LASTEXITCODE
```

这是向已确认服务器发送只读脚本，并在本机保存输出；先确认 SSH 的主机指纹，不关闭主机身份校验。没有 SSH 别名时也可以通过宝塔终端运行。不要把密码、私钥或面板登录凭据发送到聊天或写入仓库。

输出包含站点文件名、服务、路径和端口，保存在本机 `.local/`，不要提交原始报告。脚本退出 0 仅表示自动探针执行成功，**仍需下面的人工核对，才可完成 W8-D1**；它不检验部署可用性。

### 在宝塔和腾讯云控制台补充

1. 宝塔“网站”：核对现有网站、目录、监听入口和证书；确认新增应用是否复用现有 Nginx 的一个站点。只记录必要信息，不复制配置中的密钥。
2. 宝塔“数据库”与软件管理：核对数据库版本、现有业务、访问范围、备份和数据目录。复用时应为本项目建独立库、最小权限用户；是否复用需现场确认，不直接把本地开发库覆盖到线上。
3. 系统与腾讯云防火墙：核对 80/443、SSH 和面板入口；数据库 / Redis 无需面向公网开放。记录规则，盘点阶段不修改。
4. 域名 / HTTPS：确认拟用域名、解析、证书和一个同源入口。前端和 `/api` 应通过同一 HTTPS 站点提供；具体代理配置在 W8-D4 验证。
5. 保存资源余量和部署路径。脚本列出的系统资源在容器内运行时可能属于容器 / 宿主机，不能代替目标服务器检查；应在实际服务器终端运行。

## 部署方式的决定

盘点后记录所选方式及理由：优先评估复用宝塔已有入口，把应用放在确认未占用的本机端口；已有合适数据库时评估独立库与用户。资源与版本不合适时，再选择本项目独立容器数据库和 Redis。两种方式都需核对数据持久化及备份。

现在不预设另起一套公网 Nginx、不抢占 80/443、不重装系统或替换宝塔数据库。4 GB 内存还承载哪些服务尚未核对，因此不能先承诺固定容器内存分配。

W8-D1 的最终记录应包含：检查时间、系统、资源余量、现有服务与端口、域名 / HTTPS、所选入口与数据库方式、部署 / 持久化目录、已知限制。原始现场报告留在私有目录；本文件仅汇总必要且可公开的结论。

## 后续步骤与当前边界

| 日任务 | 下一步交付 | 当前状态 |
| --- | --- | --- |
| W8-D1 | 现场盘点并确定入口、端口、目录 | 准备完成；现场结果待取得 |
| W8-D2 | 镜像、Compose、持久化、内存约束与生产配置样例；本地构建和重启验证 | 本地验证通过；云端最终拓扑仍待盘点 |
| W8-D3 | 在确认位置部署、迁移、健康及云端出网验证 | 尚未部署 |
| W8-D4 | 同源 HTTPS、Cookie、登录 / 上传 / 长任务 / 通知链接验收 | 尚未验收 |
| W8-D5 | 备份与隔离恢复，独立保存凭据恢复说明 | 尚未演练 |
| W8-D6/D7 | 两用户与后台任务资源实测、完整链路及交付地址 | 尚未验收 |

现有 `compose.dev.yml`、开发 `.env` 和 Windows 启动脚本用于本地开发，不能直接作为生产发布配置。后续生产配置需明确以下已有应用要求：

- 不启用 `dev` profile、不复制开发用户或开发库；正式环境邀请码签发方式仍需补齐。
- `DB_URL` / `DB_USER` / `DB_PASSWORD` 与 Redis 连接指定生产专用数据；Spring Boot 本身不自动读取 `.env`。
- HTTPS 下使用 `SESSION_COOKIE_SECURE=true`，`CREATOR_PUBLIC_BASE_URL` 指向真实站点。
- 生产独立生成并妥善备份 `NOTIFICATION_ENCRYPTION_KEY`；它加密已保存的通知凭据，不能随意更换丢失。
- DeepSeek、MCP 密钥留在后端，构建产物、镜像和 Git 不包含密钥。
- 生成 / 复盘调度初始关闭；启用前完成模型预算配置和实际成本核对。真实 SMTP / 飞书仍待提供配置及验收。

## W8-D2 本地构建与候选配置

文件：`backend/Dockerfile`、`frontend/Dockerfile`、`frontend/nginx.conf`、`deploy/compose.yml`、`deploy/.env.example`。Dockerfile 使用本机已构建的 JAR / dist，不在 4 GB 服务器安装 Maven、Node 或执行编译。两份 `.dockerignore` 只允许所需产物和配置进入构建上下文；基础镜像固定 digest，前后端以非 root 用户运行。

候选方案使用本项目独立 MySQL / Redis 与命名卷，数据库所在网络为 internal。后端另接可出网的应用网络供未来调用模型 / MCP；不发布 MySQL、Redis 或后端端口。前端仅发布**经确认的本机端口**，可由宝塔站点代理，`/api/` 转发到后端。代理通过 Docker DNS 动态解析后端，前端健康检查同时覆盖静态页与 API，避免后端重建换 IP 后静态页仍显示健康却持续 502。

这是用于本地验证的完整候选方案。若现场决定复用宝塔数据库，需调整连接、网络与依赖后重新验证；不能在未知服务器上直接启动独立数据库。当前并没有确定线上端口、目录、数据库方式或域名。

初始容器上限为 MySQL 768 MiB、Redis 256 MiB、后端 1024 MiB、前端 64 MiB，共 2112 MiB；后端 JVM 最大堆使用容器内存的 50%，另有临时目录限制。Redis 采用 AOF、96 MiB 数据上限及 noeviction，内存不足会拒绝写入而非随意淘汰会话 / 锁。所有服务限制日志文件大小和数量。这些是本地起点，**不是目标服务器容量实测**；已有服务、Docker 和系统仍需额外资源。

### 构建顺序

本机先启动 Docker（后端 verify 使用独立 Testcontainers 测试库），再在项目根目录执行，检查每条命令退出码为 0 后再继续：

```powershell
mvn -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend run build
```

Maven 产物为 `backend/target/creator-agent-0.1.0-SNAPSHOT.jar`，前端为 `frontend/dist`；版本号变更时同步 Dockerfile 的文件名。只构建过时产物会得到过时镜像，不能跳过前面的构建。镜像不能代替数据库备份。

创建独立、忽略的 `deploy/.env`，参考 `deploy/.env.example` 填写新的密码与通知加密密钥、镜像标签、经确认的端口和公开网址。不复制开发 `.env`，不使用 `dev` profile。`docker compose config` 默认会展开凭据，因此只用 `--quiet` 检查，不要将完整输出贴进聊天或提交。

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yml build
```

仅在**本地隔离测试机或已完成盘点的目标环境**启动，使用同一项目名识别其容器和卷：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yml -p creator-agent-release up -d --wait --wait-timeout 240
docker compose --env-file deploy/.env -f deploy/compose.yml -p creator-agent-release ps
docker compose --env-file deploy/.env -f deploy/compose.yml -p creator-agent-release stop
docker compose --env-file deploy/.env -f deploy/compose.yml -p creator-agent-release up -d --wait --wait-timeout 240
```

`stop` 保留容器和卷；`down` 不带 `--volumes` 也保留命名卷。重建时保持相同项目名和环境凭据；更换项目名会得到另一组卷，更换环境密码不会自动重置已初始化数据库密码。禁止用 `down --volumes` 或卷清理作为重启步骤。

### 本地已验证的边界

- 测试项目固定为 `creator-agent-w8-package`，新生成的凭据位于忽略目录 `.local/w8-package.env`；使用 `127.0.0.1:18980` 和 HTTP，因此只在该测试中设置 Cookie Secure=false。生产样例默认 true。
- 四个容器健康，迁移到 V7.2；非 dev 环境没有自动生成开发用户。测试邀请码只在隔离库创建，并通过真实 `/api` 完成注册、登录、CSRF、账号及资料写入。
- 停止 / 启动和删除容器 / 重建均保留业务记录、Redis 标记及登录会话。最后移除测试容器与网络，保留测试命名卷；现有开发 MySQL / Redis 继续健康。
- 独立重建后端、强制改变其 IP 时，保持前端容器不变，API 健康和 CSRF 读取自动恢复。解析缓存有效期和单次解析超时均为 5 秒；这不表示容器更新零停机，也不承诺所有环境 5 秒内恢复。
- 本地镜像为 linux/amd64。实际服务器架构尚未核对，不能直接认定这些镜像可用于 ARM 服务器。
- 模型 / MCP 密钥未配置，调度关闭；没有调用外部模型或发送通知。预算开关开启，未核对费用时留空单价，使相应付费操作受阻。此处没有验收云端出网、真实通知、HTTPS 或长任务。
- 当前 PDF / 文本资料正文保存在 MySQL，原上传文件不单独保留，因此此版本没有额外资料文件卷。备份需保存数据库和独立加密密钥；恢复演练仍属于 W8-D5。

非 root NGINX 基础镜像的 8080 端口及 `/tmp` 临时路径依据 [NGINX 项目说明](https://github.com/nginx/docker-nginx-unprivileged)，动态解析依据 [NGINX upstream resolve / resolver 说明](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#server)，Java 运行镜像采用 [Eclipse Temurin 官方镜像](https://hub.docker.com/_/eclipse-temurin)。本地构建和运行验证见 [W8-D2 验收](qa/2026-10-11-w8-packaging.md)，只读盘点见 [W8-D1 准备验收](qa/2026-10-11-w8-preflight.md)，总进度见 [固定进度页](PROGRESS.md)。
