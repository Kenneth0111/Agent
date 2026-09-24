# 每日开发进度

计划：[8 周每日任务](superpowers/plans/2026-09-24-agent-daily-development-plan.md)。这里记录实际完成与验证，不把计划用时当作实际耗时。

## 2026-09-24 · W1-D1 · 已完成

- 目标：建立 Git 记录、Spring Boot 3 + Vue 3/TS 工程，验证后端健康接口及前端首页。
- GitHub：`Kenneth0111/Agent`，接入前远端没有任何分支；工作分支 `codex/w1-bootstrap`。
- 环境：Git 2.52.0、Amazon Corretto Java 21.0.9、Maven 3.9.9、Node.js 22.19.0、npm 10.9.3。
- Docker：Desktop 4.90.0 / Engine 29.7.2，实际可用；首次失败来自受限环境无法读取 Docker 配置，不是引擎未启动。
- 验证：后端 2 个测试通过并成功打包；前端 5 个测试通过，类型检查及生产构建成功。健康接口先观察到 404 的失败用例，再实现通过。
- 运行：实际启动 JAR 和 Vite，`GET /api/health` 返回 `UP`，浏览器首页显示“服务已连接”；已查看真实页面。独立代码审查无重要问题，修正“实时”状态文案。
- 环境兼容：本机 8080 已占用，本项目改用 18080；Vue 测试使用 threads 池，固定与 Node 22.19.0 兼容的 jsdom/test-utils 版本。
- 未完成：数据库、认证及 AI 接入按后续日任务推进；CI 配置已建立，远端运行结果单独确认。

### 执行决定

- Ruling: 空仓库在当前目录建立独立开发分支，不另建无历史的 worktree；没有已有代码分支需要隔离。若后续并行开发，再创建隔离 checkout。
- Ruling: 采用已确认的需求与每日任务作为授权，直接开始实现；不重新进行需求访谈。
- Ruling: 使用本文件作为可提交的长期进度账本，保留每日完成、测试证据与剩余项；本计划以日任务为单位，不依赖技能脚本对 `Task N` 标题的解析。
- Ruling: 简历属于私人参考资料，加入忽略规则；只提交项目文档、代码及配置示例，不提交任何真实凭据。
- Ruling: Git 的系统 TLS 后端在受限环境中报错，使用本仓库级 OpenSSL 后端，继续校验证书，不修改全局 Git 配置。
- Ruling: 本机已有 MySQL 3306 和 redis-stack 6379；项目开发依赖使用独立 Compose 项目、卷及 13306/16379 回环端口，不改动已有服务。

## 2026-09-24 · W1-D2 · 已完成

- 交付：专用开发 Compose、随机凭据初始化与后端启动脚本、Flyway 用户表、JPA 结构校验和 Redisson 连接。
- 依赖：MySQL 8.4（本次实际 8.4.11）、Redis 7.4 Alpine、Redisson 3.52.0、Testcontainers 2.0.5；后者使用独立临时容器测试，不连接开发库。
- 验证：先观察缺少 `users` 表导致读写用例失败；迁移实现后后端 4 项测试全部通过，`mvn verify` 成功。实际开发应用启动并返回健康 `UP`。
- 持久化：在项目开发库插入专用测试记录，重启项目 MySQL 后数量仍为 1，验证后只清理该测试记录。原有 MySQL/redis-stack 未改动。
- 凭据：生成的 `.env` 被 Git 忽略，不打印密码；数据库、缓存端口只绑定回环地址。Windows 本机脚本策略通过单个 PowerShell 进程的 RemoteSigned 参数运行，没有改全局策略。
- GitHub：W1-D1 代码已推送为 `0bd7dbb`；连接器创建草稿 PR 返回 403（集成权限不足），没有创建 PR，Git 分支推送正常。

## 2026-09-24 · W1-D3 · 已完成

- 交付：Spring Security 登录、退出、当前身份接口，Redis 会话复用 Redisson 连接，BCrypt 密码；业务侧 `CurrentUser` 只读取已验证 principal。
- 开发账号：仅 `dev` profile 创建两个 example.test 用户，密码由本机初始化脚本随机生成；重启不覆盖已有密码。普通 profile 不生成测试账号。
- 安全验证：先观察匿名身份接口应返回 401 却返回 404 的失败用例，再接通认证。完整后端 10 项测试通过，覆盖实际 HTTP 双用户隔离、错误密码、CSRF、Cookie 属性、登录轮换及退出后重放旧 Cookie 失败；同时验证开发用户密码为 BCrypt 哈希。
- 实际运行：项目开发环境用户 A/B 分别登录返回 204，当前身份 ID 不同，退出均返回 204。浏览器显示登录表单，实际提交错误密码显示明确错误并恢复可操作状态。
- 提前完成 W1-D6 的登录页面部分：登录/退出/恢复当前会话、超时和失败提示；尚未完成对话和 MCP，不勾选 W1-D6。前端 9 项测试及类型检查、构建通过。
- 审查修复：响应头到达后正文仍可能停滞；已用未关闭的响应流复现，再把超时扩展到 JSON 读取完成，回归通过。
- 下一步：W1-D4 DeepSeek 接入；当前没有可用的 DeepSeek 凭据，真实调用验收保持未完成。Tool、MCP 和腾讯云部署尚未交付。

## 2026-09-24 · W1-D4 · 部分完成（真实调用未验收）

- 交付：`ModelConfig` 按 DeepSeek 的 OpenAI 兼容接口装配 LangChain4j 1.20.0 的 `OpenAiChatModel`，`ModelGateway` 提供纯文本回复和一次结构化 JSON 解析，并把失败归一为 `MODEL_NOT_CONFIGURED` / `MODEL_AUTH_FAILED` / `MODEL_TIMEOUT` / `MODEL_UPSTREAM_FAILED` / `MODEL_INVALID_OUTPUT`。
- 依赖：`dev.langchain4j:langchain4j-open-ai:1.20.0`，运行时用 JDK HttpClient，未引入 Spring Boot starter，避免与 Boot 3.5 的自动配置耦合。
- 验证：后端 18 项测试通过（`mvn -f backend/pom.xml test`）。`ModelGatewayTest` 用本机临时 HTTP 服务模拟 OpenAI 兼容响应，覆盖文本回复、JSON 必填字段、非 JSON 与空字段、401、500、超时边界及未配置凭据；`ModelWiringTest` 用真实 Spring 上下文确认凭据为空时应用仍能启动且调用明确被拒绝。
- 脱敏：上游错误正文（测试中为 `upstream secret details`）不出现在异常消息和日志中，日志只记录操作名、错误码、异常类型、耗时和 token 用量。
- 未完成：没有可用 DeepSeek 凭据，一次真实回复与真实用量记录仍未取得，W1-D4 不勾选。付费调用一律不自动重试，重试策略留到 W5 的任务状态机。

### 执行决定

- Ruling: 付费调用设 `maxRetries(0)`。客户端静默重试会重复计费且掩盖超时后结果未知的情况，重试交给后续持久任务按状态决定。
- Ruling: 失败只向调用方暴露稳定错误码，不透传供应商错误正文，避免密钥或上游细节进入日志与接口响应。
- Ruling: 凭据缺失不阻止应用启动，而是在调用时返回明确的未配置错误；本机与 CI 都没有凭据，启动失败会挡住其他任务的开发和验证。
