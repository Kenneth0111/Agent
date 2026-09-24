# Creator Agent · 创作工作台

一个逐步开发中的 Java 内容创作与运营 Agent。目标是支持资料检索、选题、脚本、周排期及基于真实数据的复盘。

当前已支持登录、退出、当前用户身份和服务连接检查，并接入 MySQL、Redis/Redisson。抖音接入和 AI 生成功能仍在后续计划中。

## 本地环境

已验证的开发环境：Java 21.0.9、Maven 3.9.9、Node.js 22.19.0、npm 10.9.3。

主要版本固定在 `backend/pom.xml`、`frontend/package.json` 和 `frontend/package-lock.json`：Spring Boot 3.5.16、Vue 3.5.43、Vite 8.3.0、TypeScript 5.9.3。

## 启动

先启动 Docker Desktop，再从项目根目录初始化专用开发依赖：

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File scripts/init-dev.ps1
docker compose -f compose.dev.yml up -d --wait
```

初始化脚本只在 `.env` 不存在时生成随机密码，不会覆盖已有凭据。`RemoteSigned` 只作用于该 PowerShell 进程，不修改系统策略。MySQL 使用 13306，Redis 使用 16379，仅绑定本机，使用本项目独立数据卷。`docker compose -f compose.dev.yml stop` 可停止服务而保留数据。

从项目根目录打开两个终端。

后端：

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File scripts/start-backend.ps1
```

前端：

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev
```

打开 <http://127.0.0.1:5173>。前端将 `/api` 转发到 `http://127.0.0.1:18080`，默认只监听本机。`GET /api/health` 成功返回 `{"status":"UP"}`。

关闭后端再点击“重新检查”，页面应显示无法连接；恢复后端再试，应恢复已连接。服务状态不代表登录、模型或抖音能力已接通。

后端可通过进程环境变量设置 `SERVER_ADDRESS` 和 `SERVER_PORT`；修改后端端口时同步调整 `frontend/vite.config.ts` 的代理地址。`.env.example` 是配置说明；启动脚本会把 `.env` 导入子进程，Spring Boot 本身不会自动读取它。Linux 环境可以导出相同变量后运行 Maven。

启动时由 Flyway 执行版本化数据库迁移，Hibernate 只校验结构。

## 开发账号与登录

初始化脚本生成 `DEV_USER_PASSWORD`。只有启用 `dev` profile 时，后端才会创建 `creator-a@example.test`、`creator-b@example.test` 两个开发账号；密码在本机 `.env` 查看，不写入文档、日志或仓库。数据库只保存 BCrypt 哈希。重启不会覆盖已有账号密码；修改 `.env` 不会重置数据库中的旧账号。

如果 `.env` 来自早期版本，请手动增加 `SPRING_PROFILES_ACTIVE=dev` 和一个至少 16 字符的随机 `DEV_USER_PASSWORD`，不要重新生成数据库密码。正式环境使用独立数据库，不启用 `dev`，通过邀请注册创建用户；邀请功能尚未实现。

浏览器用同源 `/api` 访问后端，会话保存在 Redis，通过 HttpOnly、SameSite=Lax 的 `CREATOR_SESSION` Cookie 传递，闲置 30 分钟过期。HTTPS 部署时配置 `SESSION_COOKIE_SECURE=true`。

接口：`GET /api/auth/csrf` 获取 CSRF header/token；`POST /api/auth/login` 提交 `application/x-www-form-urlencoded` 的 `email`、`password` 及 CSRF header；`GET /api/auth/me` 返回当前身份；`POST /api/auth/logout` 携带重新获取的 CSRF token 退出。登录时轮换会话，退出使旧会话失效；业务身份从后端登录上下文获取，请求里的 `userId` 不改变身份。

## 验证

```powershell
mvn -f backend/pom.xml test
mvn -f backend/pom.xml package
npm --prefix frontend test
npm --prefix frontend run build
```

后端测试需要运行中的 Docker：Testcontainers 自动建立并回收独立的 MySQL/Redis 临时容器，不读取开发 `.env`，也不连接开发或生产数据库。首次运行需下载镜像。测试验证健康接口、用户表读写与邮箱唯一性、Redisson 读写和 TTL，以及真实 HTTP 双用户登录隔离、错误密码、CSRF、Cookie 属性、会话轮换和退出后 Cookie 重放失败。

前端测试覆盖后端正常、网络失败及重试、HTTP 错误、异常 JSON 和代理返回 HTML 的状态处理，以及登录、退出、CSRF 更新和登录失败提示。

接入实现参考：[Spring Security 会话管理](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)、[CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)、[Redisson 配置](https://redisson.pro/docs/configuration/)、[Testcontainers MySQL](https://java.testcontainers.org/modules/databases/mysql/)。

## 进度与提交

- [每日开发记录](docs/development-log.md)：实际完成、测试证据、问题及下一步。
- [需求与实施计划](docs/项目需求与实施计划.md)：已确认范围。
- [8 周每日清单](docs/superpowers/plans/2026-09-24-agent-daily-development-plan.md)：每日目标及验收标准。

每个独立的可验证增量提交一次；只有通过验收才勾选计划。计划中的“天”是工作单元，不要求 AI 开发等待到下一自然日。未完成的外部接入明确记录，不用模拟结果代替实际验收。

工作分支使用 `codex/w1-bootstrap`，主分支 `main` 保存已提交基线。GitHub 推送不等于腾讯云部署。

私人简历、`.env`、缓存、依赖、日志及构建产物均在忽略规则中。开发过程不需要将密钥发到聊天或提交到 GitHub。
