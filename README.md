# Creator Agent · 创作工作台

一个逐步开发中的 Java 内容创作与运营 Agent。目标是支持资料检索、选题、脚本、周排期及基于真实数据的复盘。

当前已支持邀请注册、登录、按用户隔离的内容账号与资料、个人资料优先检索、Java 面试及英语跟读选题/脚本生成、整周草稿和定向修改。开发环境已用 DeepSeek 完成真实内容联调。抖音指标接入、周日历及自动运行仍在后续计划中。

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

初始化脚本生成 `DEV_USER_PASSWORD` 和 `DEV_INVITE_CODE`。只有启用 `dev` profile 时，后端才会创建 `creator-a@example.test`、`creator-b@example.test` 两个开发账号，并将开发邀请码的 SHA-256 摘要存入数据库，默认有效期 7 天。密码和邀请码只在本机 `.env` 查看，不写入文档、日志或仓库。数据库只保存 BCrypt 密码哈希和邀请码摘要。重启不会覆盖已有账号或再次延长邀请码；修改 `.env` 不会重置数据库中的旧记录。

如果 `.env` 来自早期版本，请手动增加 `SPRING_PROFILES_ACTIVE=dev`、一个至少 16 字符的随机 `DEV_USER_PASSWORD` 以及随机 `DEV_INVITE_CODE`，不要重新生成数据库密码。正式环境使用独立数据库且不启用 `dev`；当前已实现受邀注册，但正式环境的邀请码签发后台尚未实现。

浏览器用同源 `/api` 访问后端，会话保存在 Redis，通过 HttpOnly、SameSite=Lax 的 `CREATOR_SESSION` Cookie 传递，闲置 30 分钟过期。HTTPS 部署时配置 `SESSION_COOKIE_SECURE=true`。

接口：`GET /api/auth/csrf` 获取 CSRF header/token；`POST /api/auth/login` 提交 `application/x-www-form-urlencoded` 的 `email`、`password` 及 CSRF header；`GET /api/auth/me` 返回当前身份；`POST /api/auth/logout` 携带重新获取的 CSRF token 退出。登录时轮换会话，退出使旧会话失效；业务身份从后端登录上下文获取，请求里的 `userId` 不改变身份。

`POST /api/auth/register` 同样需要 CSRF token，提交 JSON 的 `invitationCode`、`email`、`displayName` 和至少 12 位的 `password`。邀请码只保存 SHA-256 摘要，注册通过单条条件更新原子占用邀请码，并在同一事务创建用户：无效、过期、已用、重复邮箱和格式错误均返回 `REGISTRATION_REJECTED`，不会泄露邀请码或邮箱是否存在。前端注册成功后要求重新登录，不自动创建会话。

## 验证

周排期接口：`POST /api/schedules/weeks` 接收 `accountId` 与周一日期 `weekStart`（`YYYY-MM-DD`），为账号创建该周排期；同一账号同一周再次提交返回原排期。`GET /api/schedules/weeks?accountId=...&weekStart=...` 读取该周，`PUT /api/schedules/items/{id}/date` 接收 `expectedVersion` 与 `scheduledDate` 修改发布日期。日期按 `Asia/Shanghai` 的本地日历解释，只能落在所属周；版本冲突返回 409。初始计划项预留选题与脚本引用，新建时为空，后续生成流程负责关联。

脚本确认接口：`POST /api/generations/scripts/{id}/confirm` 接收 `expectedVersion`，将待审阅草稿标为 `CONFIRMED`；`POST /api/generations/scripts/{id}/reopen` 用相同字段显式重新开放编辑。两种操作都会递增版本，旧版本返回 409；确认后的修改请求在调用模型前被拒绝。再次生成脚本会保存新的候选稿，不覆盖已有确认稿。

```powershell
mvn -f backend/pom.xml test
mvn -f backend/pom.xml package
npm --prefix frontend test
npm --prefix frontend run build
```

后端测试需要运行中的 Docker：Testcontainers 自动建立并回收独立的 MySQL/Redis 临时容器，不读取开发 `.env`，也不连接开发或生产数据库。首次运行需下载镜像。测试验证健康接口、用户表读写与邮箱唯一性、Redisson 读写和 TTL，以及真实 HTTP 双用户登录隔离、错误密码、CSRF、Cookie 属性、会话轮换和退出后 Cookie 重放失败。

前端测试覆盖后端正常、网络失败及重试、HTTP 错误、异常 JSON 和代理返回 HTML 的状态处理，以及登录、退出、CSRF 更新和登录失败提示。

## 模型网关

`DEEPSEEK_API_KEY` 为空时后端仍能启动，网关对每次调用返回 `MODEL_NOT_CONFIGURED`，不会静默降级成假回复。失败按 `MODEL_AUTH_FAILED`、`MODEL_TIMEOUT`、`MODEL_UPSTREAM_FAILED`、`MODEL_INVALID_OUTPUT` 区分；供应商原始错误正文不进入接口响应和日志。付费调用不自动重试，是否重试由任务状态决定。JSON 结果缺字段或不是 JSON 都判为失败，不保存为内容。

模型测试使用本机临时 HTTP 服务模拟 OpenAI 兼容响应，不发生真实付费调用。

验收真实供应商接入需要把密钥写进本机 `.env` 的 `DEEPSEEK_API_KEY`（不要贴进聊天、文档或提交），然后运行：

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File scripts/verify-model.ps1
```

这会发生 2 次真实付费调用：一次短文本回复，一次结构化 JSON 解析。没有密钥时 `DeepSeekLiveCallTest` 会被跳过，跳过不算接入已验证。

## 本地 Tool 与图流程

`read_account_profile` 是只读工具，返回账号的定位、栏目和每周条数。工具实例在每次运行时用登录得到的 userId 构造，模型给出的 `accountId` 只能在该用户自己的账号里查找；不属于本人的账号与不存在的账号都返回 `ACCOUNT_NOT_FOUND`，不泄漏其他用户是否有该账号。未知工具名返回 `UNKNOWN_TOOL`，参数不可解析返回 `TOOL_ARGUMENTS_INVALID`，两种情况都交回模型说明而不是直接执行。

账号配置保存在 MySQL 的 `content_accounts` 表。登录用户可从页面创建、查看和编辑多个内部内容账号，字段包括名称、目标受众、定位、栏目和每周条数。建档不要求先注册或授权抖音账号。`GET /api/accounts`、`GET /api/accounts/{id}`、`POST /api/accounts` 和 `PUT /api/accounts/{id}` 均从登录态取得 ownerId；其他用户的账号详情和修改统一返回 404。开发环境仅在示例用户还没有账号时创建 Java 八股与托福跟读账号及独立测试号，正式环境没有示例账号。Agent 的只读账号工具使用同一数据库目录。

## 资料导入

登录后可直接粘贴文字、选择 TXT/Markdown 文件、上传可提取文字的 PDF，或保存链接及用户填写的摘录。文字正文最多 100 KiB，PDF 文件最多 5 MiB；扫描件不做 OCR，无法提取文字时明确拒绝。链接只保存为出处，不自动抓取网页。每份资料可关联当前用户的多个内容账号，后端验证账号归属。原文及最多 1000 个 Unicode 字符一段的检索片段保存在 MySQL；资料列表不传输全文，点击预览时才读取原文。

接口为 `GET /api/materials`、`GET /api/materials/{id}`、`POST /api/materials`（JSON，`kind=TEXT/LINK`）、`POST /api/materials/pdf`（multipart）、`DELETE /api/materials/{id}`。创建和删除需要 CSRF token。所有操作从登录态取得 ownerId；访问或删除他人资料统一返回 404，关联他人账号同样返回 404。删除资料时数据库同步删除其检索片段及账号关联。

`GET /api/materials/search?accountId=...&q=...` 按登录用户与所选账号检索标题、文字片段，最多返回 10 份资料及片段、来源链接或文件名。未关联账号的旧资料视为当前用户的通用资料；关联了账号的资料只出现在对应账号的结果里。无命中返回 `status=INSUFFICIENT_MATERIAL` 与空列表，不伪造出处。关键词检索不保证语义匹配；只读 `search_my_materials` Tool 已接入 `ResearchWorkflow` 的本地检索节点。

`ContentWorkflow` 用 LangGraph4j 串联读取账号配置和生成摘要两个节点。工具调用最多 3 轮，超出按 `AGENT_TOOL_LIMIT` 结束；图另有步数上限作为第二道保护，因此不会无限调用。工具执行在日志中记录工具名、状态和耗时。

## 对话与 MCP 搜索

登录后，工作台会读取当前用户自己的账号，并可请求一条账号创作摘要。`POST /api/agent/research` 按所选账号先检索个人资料；有命中时只把最多 3 个片段作为不可信数据交给模型，回答和实际片段出处一同返回。本地无命中时才把问题文本发送给已配置的 Tavily MCP 搜索服务；网页结果最多取 3 条带 URL 的片段，页面标记“网页来源 · 未经核实”。两处都无可用证据时不调用模型，也不编造答案。MCP 未配置或故障时仍返回 `INSUFFICIENT_MATERIAL`，并在 `webSearchStatus` 标出原因。后端会在运行工作流前检查账号归属；未配置模型时页面显示“模型尚未配置”，不会生成固定示例内容。

MCP 采用 LangChain4j 的 Streamable HTTP Client。`GET /api/agent/mcp/tools` 只发现由 `MCP_SEARCH_ALLOWED_TOOLS` 显式允许的工具；执行路径固定为 `tavily_search`，只传问题、基础搜索深度和最多 5 条的服务端结果上限，用户请求不能指定 MCP 地址、认证头或工具名。第三方服务不会取得账号资料、会话或资料库内容。在本机 `.env` 设置 `MCP_SEARCH_URL=https://mcp.tavily.com/mcp`、`MCP_SEARCH_BEARER_TOKEN=<你的 Tavily API Key>`、`MCP_SEARCH_ALLOWED_TOOLS=tavily_search` 后重启后端即可尝试连接。空配置返回 `MCP_NOT_CONFIGURED`，连接或协议失败返回 `MCP_UNAVAILABLE`；不要把密钥提交到仓库。

搜索适配按 [Tavily 官方 MCP 工具定义](https://github.com/tavily-ai/tavily-mcp/blob/main/src/index.ts)和[官方结果格式](https://github.com/tavily-ai/tavily-mcp/blob/main/src/format-results.ts)实现，同时接收远程服务实际返回的 JSON `results`。本机已用用户提供的私有凭据完成远程工具发现与真实搜索：资料不足时取得 3 条带 URL 的网页来源并生成回答；本地资料命中时只引用本地来源。凭据只在被 Git 忽略的 `.env` 中，其他机器与服务器仍需自行配置。外部搜索可能产生供应商调用费用，请核对 Tavily 账户额度。

## 内容生成与修改

V4/V4.1/V4.2/V4.3 迁移保存选题、脚本、生成运行、失败节点、会话、旧版本和整周草稿。`GenerationGraph` 用 LangGraph4j 执行 `readAccount → retrieveEvidence → generateDraft → validateDraft → saveDraft`；来源 ID 只能选当前用户、当前账号可用的资料。模型输出结构错误最多修正一次，失败运行可查询 `errorCode` 与 `failedNode`。这不是可恢复执行的 LangGraph checkpoint。

登录后在“内容生成”选择账号、栏目和最多 3 份参考资料，输入要求并生成选题；选择已保存选题后可生成脚本。没有匹配资料时允许保存标注“待核实”的选题，但不能生成无依据的脚本。`POST /api/generations` 使用 `mode=TOPICS` 或 `SCRIPT`，返回运行记录；`GET /api/generations/{id}` 查询运行。`GET /api/generations/topics?accountId=...` 和 `GET /api/generations/scripts?topicId=...` 让刷新页面后仍能读取草稿。

`POST /api/generations` 的 `mode=WEEK_PLAN` 接收固定 3 个 `slots`（前两条 `Java 面试`，第三条 `英语跟读`），每条指定参考 `materialIds`；成功后产生 3 组选题与脚本及一个整周草稿 ID。`GET /api/generations/week-plans?accountId=...` 与 `GET /api/generations/week-plans/{id}` 可读取。整周生成目前为同步串行调用；若中途失败，已完成的单条草稿仍保留，整周草稿不会保存，重试可能产生重复单条草稿。正式用于定时运行前需加入请求幂等和断点续跑。

`POST /api/generations/scripts/{id}/revise` 接收 `expectedVersion`、`instruction` 和可选的 `conversationId`，返回新版本与会话 ID；同一会话的最近 3 条修改要求进入模型上下文。旧版本在 `GET /api/generations/scripts/{id}/versions` 可查，来源 ID 必须保留；旧版本号写入返回 409。所有读写从登录态限定用户，跨用户脚本或会话不会进入模型上下文。内容生成和修改会真实调用模型，请先在 `.env` 设置 DeepSeek API Key。

[W3 内容联调记录](docs/qa/2026-09-25-w3-generation.md)包含 3 道 Java 题和 1 条英语跟读的核对结果。脚本时长目前只由提示词引导，正式发布前仍需人工检查。

接入实现参考：[Spring Security 会话管理](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)、[CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)、[Redisson 配置](https://redisson.pro/docs/configuration/)、[Testcontainers MySQL](https://java.testcontainers.org/modules/databases/mysql/)、[LangChain4j MCP](https://docs.langchain4j.dev/tutorials/mcp/)。

## 进度与提交

- [每日开发记录](docs/development-log.md)：实际完成、测试证据、问题及下一步。
- [需求与实施计划](docs/项目需求与实施计划.md)：已确认范围。
- [8 周每日清单](docs/superpowers/plans/2026-09-24-agent-daily-development-plan.md)：每日目标及验收标准。

每个独立的可验证增量提交一次；只有通过验收才勾选计划。计划中的“天”是工作单元，不要求 AI 开发等待到下一自然日。未完成的外部接入明确记录，不用模拟结果代替实际验收。

工作分支使用 `codex/w1-bootstrap`，主分支 `main` 保存已提交基线。GitHub 推送不等于腾讯云部署。

私人简历、`.env`、缓存、依赖、日志及构建产物均在忽略规则中。开发过程不需要将密钥发到聊天或提交到 GitHub。
