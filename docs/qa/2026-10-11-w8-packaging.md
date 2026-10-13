# W8-D2 本地镜像与持久化验收

日期：2026-10-11。目标服务器尚未提供连接方式；本轮全部运行在本机 Docker Desktop，没有云端部署、宝塔变更或真实 HTTPS 验收。

## 构建结果

- 业务代码未变更。此前 W7 相关后端测试 57 项及前端 74 项已通过；本轮后端用已缓存依赖 `mvn -o -Dmaven.repo.local=... -f backend/pom.xml -DskipTests package` 重新打包成功，没有把该命令计作新业务测试通过。
- `npm --prefix frontend run build` 的 Vue 类型检查及 Vite 生产构建通过。
- 两份 Dockerfile 的构建上下文仅允许 JAR / dist 与指定配置，不含本机 `.env`；Java 和 NGINX 基础镜像固定 digest。四个基础镜像的完整标识见 Dockerfile / Compose。
- `docker compose --env-file .local/w8-package.env -f deploy/compose.yml -p creator-agent-w8-package config --quiet` 与 `build` 退出 0。
- 镜像 `creator-agent-backend:w8-local-20261011` 为 linux/amd64、USER=10001:10001、约 179 MB；前端 `creator-agent-web:w8-local-20261011` 为 linux/amd64、USER=101、约 23 MB。这是 Docker 镜像 Size 字段，非传输压缩量或运行内存。

## 实际运行验证

在独立 `creator-agent-w8-package` 项目、新命名卷、新随机数据库 / Redis 密码和通知加密密钥下运行。没有挂载开发数据卷，不启用 dev profile，不配置模型 / MCP 密钥；调度关闭，预算保护开启。

`up -d --wait --wait-timeout 240` 退出 0，MySQL / Redis / backend / web 全部 healthy。后端运行日志证实 MySQL 8.4.11、迁移到 V7.2、后端监听容器 8080、Redisson 成功连接私网 Redis。实际前端唯一发布地址为 `127.0.0.1:18980`，数据库 / Redis / 后端无宿主端口。

验证过程及结果：

1. `/` 返回生产静态页，代理后的 `/api/health` 返回 UP。新库 users 数量 0、失败迁移数 0，确认没有复制开发账号。
2. 仅在测试库插入一个测试邀请码的 SHA-256 摘要，通过 HTTP 获取 CSRF、注册 `w8-package@example.test`、登录 204、刷新 CSRF，并创建账号与文本资料。完整会话使用同源 Cookie，未跳过安全过滤器。
3. Redis 写入独立标记 `w8:package:persistence`；`stop` 后 `up --wait`，同一客户端仍可读取本人身份、账号、资料，Redis 标记保留。
4. `down`（不含 --volumes）后按相同项目名 `up --wait`；同一检查再次通过，证明重建容器仍使用原命名卷，Redis AOF 会话也保留。
5. 最后 `down` 移除测试容器 / 网络，`creator-agent-w8-package_mysql-data` 与 `creator-agent-w8-package_redis-data` 保留；原开发 MySQL / Redis 继续 healthy。

本机执行脚本及日志留在忽略目录 `.local/w8-package-verify.ps1`、`.local/w8-persistence-restart.log`、`.local/w8-package-result.json`；配置、密码、Cookie / 邀请码不公开。该一次性脚本仅用于新测试栈，不用作生产初始化或迁移脚本。

实际保留测试记录：账号 `b02971b8-1d0b-47ec-bb4f-504c67f5adc7`、资料 `4c4f4487-e76e-40dd-ac38-9f668d465212`；资料正文为明确的隔离测试标记，不代表抖音运营数据。验证时间 `2026-10-11T14:26:47Z`。

## 后端单独重建回归

独立只读审查发现静态 proxy_pass 只在启动时解析后端地址。实际测试停止并移除测试后端，用临时容器保留旧应用网络 IP，重新启动后端以确保新 IP 不同；前端容器 ID 保持不变。

旧配置：`172.20.0.2 → 172.20.0.4` 后代理持续 502，回归先失败。修正为 upstream zone + resolve、Docker DNS、5 秒缓存有效期及单次解析超时；前端健康检查也从仅静态页改为同时覆盖 API。依据 [NGINX 官方模块说明](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#server)。

定位过程中还发现 PowerShell 将 Actuator 的 `application/vnd.spring-boot.actuator.v3+json` 作为 byte[] 返回；测试客户端必须先 UTF-8 解码，否则会误判 200 响应。未因此改变业务接口。默认解析超时与缓存有效期是不同设置，不把 valid=5s 当作固定恢复时限。

最终运行：`172.20.0.4 → 172.20.0.3`，前端未重建；后端健康后首次代理请求返回 200 / UP，CSRF 路径读取正常，回归退出 0。`nginx -t` 成功；测试库 usage_calls 为 0。测试过程及命令留在 `.local/w8-proxy-ip-test.ps1`、`.local/w8-proxy-ip.log`。临时占位容器始终在 finally 移除；这是有计划停机的本地回归，不是滚动更新零停机承诺。

最终镜像：backend `sha256:471aba02cc3468e91861a76487ce4405257aa7ea84fd25cfeade1bf34531c080`，web `sha256:e1608582e773eca88de30a203d1a30b4fe7651bf356d496bd79831f20bab633d`。NGINX 修正后仅重建 web；后端业务代码未变更。

最终代理再次实际注册独立用户 `w8-proxy@example.test`，登录 204、Cookie 身份读取一致、访问前一用户账号返回 404。随后仅对测试项目执行 down，不含 --volumes；两卷保留，原开发容器 healthy。独立最终复核确认原 DNS P2 已解决，无新增重要问题；没有运行 W8-D6 的双用户并发后台资源验收。

## 配置边界与未验收项

- 解析配置后核对：data 为 internal 网络，唯一发布端口是 loopback web；测试后端没有 dev profile 或模型 / MCP 凭据。镜像环境元数据中不存在数据库、Redis、通知加密或供应商密钥的配置项。
- MySQL / Redis 使用独立命名卷，后端 / 前端根文件系统只读并设置临时目录。资料正文存数据库，原上传文件未单独存盘，因此当前无需虚构资料文件卷。
- 容器初始内存上限总计 2112 MiB，不是实际峰值或云端容量证明。没有执行 W8-D6 的双用户后台任务资源测量。
- 这里的 HTTPS、代理头只做配置准备；实际 HTTPS Cookie、长任务、云端出网、真实通知和隔离备份恢复未验收。
- 独立数据库方案是候选拓扑。实际服务器盘点后若复用宝塔库或采用另一入口，需要调整并重新验证；W8-D1 仍部分完成，W8-D3 起未部署。
