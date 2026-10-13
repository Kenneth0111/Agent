# W7-D6 每周复盘配置与执行验收

日期：2026-10-11。范围：每账号每周复盘设置、固定窗口、持久去重、模型公共链路、恢复核对、页面与用户隔离。与 [数据页验收](2026-10-11-w7-data-ui.md)合并构成 W7-D6 证据。

## 后端验证

```powershell
mvn -o "-Dmaven.repo.local=D:\AllCode\Project\Agent\.cache\m2" -f backend/pom.xml "-Dtest=ReviewJobTest,OperationReviewIntegrationTest,OperationReviewHttpTest,GenerationJobsServiceTest" verify
```

28 项、0 失败/错误/跳过，JAR 打包成功（ReviewJobTest 7、模型协议 16、HTTP 2、原定时生成 3）。数据库与 Redis 是隔离容器；模型是本地真实 HTTP 协议替身，不是本轮真实 DeepSeek 自动定时调用。

| 行为 | 证据 | 结果 |
| --- | --- | --- |
| 到点前不运行，晚到仍按计划时刻窗口，无数据跳过模型 | ReviewJobTest | 通过 |
| 重复扫描/并发预约一个周期一次；配置更新保留 ID 和旧请求，下周使用新参数 | ReviewJobTest | 通过 |
| 禁用不运行；服务总开关 false 不运行，显式启用入口才扫描 | ReviewJobTest、OperationReviewHttpTest | 通过 |
| DST 7 个本地日可为 169 小时，共享锁繁忙不预约 | ReviewJobTest | 通过 |
| 启动/扫描核对已有终态报告；未知周期不重发；关联中断报告同步 NEEDS_REVIEW，事实/次数保留 | ReviewJobTest | 通过 |
| 手动复盘也等待共享锁，无数据库预约事务覆盖等待和网络 | ReviewJobTest、OperationReviewIntegrationTest | 通过 |
| 定时图成功一次调用，账本 taskId 为报告 ID；重复扫描不追加调用 | OperationReviewIntegrationTest | 通过 |
| 认证/上游失败/超时保存失败或需核对状态，保留事实；周期不再次调用 | OperationReviewIntegrationTest | 通过 |
| 配置/记录/报告的 Cookie、CSRF、跨用户 404、非法时区 400 | OperationReviewHttpTest | 通过 |
| 原周内容生成与共享锁行为回归 | GenerationJobsServiceTest | 通过 |

首次链路测试因缺少类失败；完成实现后发现测试夹具误用了不存在的 app_users，改为既有 users 表，链路通过。独立审查发现 trigger 与关联报告恢复状态不同步；增加状态/错误码断言先失败，再同步更新并给手动复盘加共享锁，最终复审与验证通过。恢复未重发模型。

## 前端与实际浏览器

前端完整 `npm test`：15 个文件、73 项全部通过；`npm run build` 的类型检查与生产构建通过。覆盖默认停用、服务器开关提示、保存不触发、刷新保留未保存设置、迟到响应忽略、跨账号响应拒绝、周期状态/错误码和已保存报告只读查看。

Playwright CLI 独立 Edge 会话真实登录开发后端/API，无模拟配置响应：

- V7.2 迁移成功、健康 200。
- 仅在既有 `DEMO · W7 数据页验收` 账号保存周日 09:00、Asia/Shanghai、关键词“线程池”的 enabled=false 配置。jobId `ea050b76-9ba5-495c-86be-301152d29d6e`；服务器 schedulerEnabled=false，周期记录 0。
- 将输入改为 Redis 后刷新状态：输入保留 Redis，提示“保留尚未保存的配置”；切到原账号后输入清空、无配置/周期，切回 DEMO 重新读取已保存的“线程池”。
- 手机宽 390、document.scrollWidth=390，无整页横向溢出。桌面与手机截图已实际查看：忽略目录 `output/playwright/w7-review-jobs/.playwright-cli/element-2026-10-11T08-56-02-327Z.png` 和 `element-2026-10-11T08-56-14-513Z.png`。
- DEMO 账号报告仍为前轮的 2 份，关联账本仍为前轮 1 次真实手动调用；本轮没有新增真实付费调用、自动扫描或通知。周期执行记录及点击报告的渲染由组件测试、真实 HTTP/容器执行验证，开发库保持 0 个实际周期。

独立审查/复审通过；最终开发服务重启加载共享锁和恢复修正，总开关仍默认关闭。辅助脚本、登录会话、截图仅在忽略目录，不提交凭据。

## 完成与限制

W7-D6 的数据页、完整导入、冷启动/重复/隔离、手动真实模型与定时逻辑演练已经完成。当前未启用开发/生产真实自动付费调度；没有实际抖音运营样本和官方持续同步验收，没有真实运营效果结论。W7-D7 继续核对口径与官方接口接入现状，W8 仍为部署与恢复验收。
