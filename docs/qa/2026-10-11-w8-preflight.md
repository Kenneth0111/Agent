# W8-D1 服务器盘点准备验收

日期：2026-10-11。范围为本地部署准备；没有连接腾讯云或修改宝塔服务。

## 交付与限制

- `scripts/server-preflight.sh`：只读 Linux 系统 / 资源 / 端口 / 宝塔配置文件名 / 服务 / 本机 Docker 摘要。缺失工具与失败命令继续收集、退出 2，必须人工补充；全部自动命令成功也不代表可部署。
- `docs/deployment.md`：固定部署记录入口、待核对表、SSH / 宝塔终端使用方式、服务共存决策及 W8 后续验收要求。README 加入入口。
- 未取得目标地址 / 连接方式、实际资源、网站 / 数据库 / 防火墙、域名或 HTTPS 信息；W8-D1 保留部分完成，W8-D2 起未勾选。

## 验证

在本地已有 `ubuntu:24.04` 镜像创建独立容器，禁用网络、根文件系统只读、丢弃 capabilities、禁止提升权限。只挂载检查和测试脚本两个文件，均只读；测试夹具使用容器临时目录。不挂载项目 `.env`、密钥或业务数据，不接入开发 MySQL / Redis。

```powershell
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:rw,nosuid,nodev,exec --mount 'type=bind,source=D:\AllCode\Project\Agent\scripts\tests\server-preflight-test.sh,target=/test.sh,readonly' --mount 'type=bind,source=D:\AllCode\Project\Agent\scripts\server-preflight.sh,target=/server-preflight.sh,readonly' ubuntu:24.04 bash -c 'bash -n /server-preflight.sh && bash -n /test.sh && bash /test.sh /server-preflight.sh'
```

结果：退出 0，五组 PASS。

1. 最小 Linux 缺少 `ss` / Docker 等工具时退出 2，报告仍包含 OS、端口检查与人工核对提示，不包含测试环境密钥。
2. 注入执行失败的 `free` 命令，输出 `[FAILED] free` 而非健康结论，继续收集后续信息。
3. 设置指向外部的 `DOCKER_HOST`，Docker 测试夹具确认 `info` / `ps` 使用固定本机 Unix socket；服务描述中的测试密钥不进入报告。
4. 注入 Java 启动选项，版本查询不回显 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`。
5. 模拟 PowerShell 原生管道追加 CRLF，在自动探针均成功时仍明确退出 0，不执行额外 CR 字符而误报退出 127。

先运行未实现脚本的测试，退出 1（检查脚本缺失）。实现后，异常测试发现测试临时目录默认不可执行以及服务探针中提前退出，定位并修正；Java 参数测试先失败（回显测试密钥），再清除该子进程的 JVM 选项，全部通过。MySQL 版本查询禁用默认配置文件读取。

独立只读审查发现 PowerShell 管道末尾追加 CRLF 的退出码问题；先用 Linux 测试复现成功信息之后退出 127，再为成功路径加显式 `exit 0`。审查其余信息边界与状态标记未发现重要问题。

这些测试验证本地脚本行为与信息边界，不能证明目标服务器的工具、权限、网络和资源状态。没有执行安装、生产迁移、模型调用或外部通知。脚本实际现场盘点结果应保存私有目录，不能把本地容器资源当作腾讯云资源。
