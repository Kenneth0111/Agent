# 数据库备份与隔离恢复

适用范围：`deploy/compose.yml` 的候选 MySQL 栈。**已完成本地真实恢复演练，未执行腾讯云备份或生产恢复。** 如果现场选择复用宝塔数据库，需要按其实际连接和权限调整并重新验证。

## 备份内容与前提

当前资料正文、PDF 提取文本及来源、账号、计划、任务、预算与通知记录均在 `creator_agent` 数据库；原上传文件没有单独保留，因此当前没有额外资料文件卷。SQL 不包含 MySQL 系统账号、Redis、镜像、环境文件或通知加密密钥。Redis 会话与锁使用新的隔离实例，用户重新登录；数据库持久状态仍需核对，不能把恢复视作重发付费任务的许可。

Linux 维护环境需要 Docker Compose、sh、mktemp、sha256sum、sed；MySQL 客户端来自现有 MySQL 容器，无需安装本机数据库。开始前确认 Docker 当前连接目标与项目名，脚本遵循当前 Docker context，不自动切换主机。Windows 本地演练通过 Linux 工具容器保存原始字节流，不用 PowerShell 的 SQL 文本重定向。备份目录保存业务内容和密码哈希，使用私有目录，不提交 Git。

导出使用 `--single-transaction --quick --no-tablespaces --set-gtid-purged=OFF`、UTF-8 和应用数据库账号。当前表为 InnoDB；导出期间不要并行执行结构迁移。此方式不提供 MyISAM 一致性或按时间点恢复，选项及限制依据 [MySQL 8.4 mysqldump 说明](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)。

## 创建备份

仅对已确认的候选项目执行，保持原容器正在运行。三个参数分别为源项目名、其环境配置文件、私有输出目录：

```sh
sh deploy/backup.sh creator-agent-release deploy/.env .local/backups
```

脚本只执行数据库导出，不停止应用或改动源数据。每次创建新的私有目录，包含 `database.sql`、`database.sql.sha256`、`database.sql.source`；最后一个是格式与源项目标记。SQL 导出失败会移除该次 partial 文件；后续校验或标记写入失败可能留下不完整目录，不能作为成功备份。

保存完整三件套。校验值用于发现文件损坏，不能证明文件来自可信来源；仅恢复自己控制的备份。备份不压缩，云端目录、空间、权限、异机副本与保留期限仍须现场确定，不用清理旧业务数据作为演练。

## 独立保存配置与密钥恢复说明

在 SQL 以外的私有位置保留环境恢复说明，记录其对应部署版本 / 备份时间，不把真实值写到本文件或仓库：

- 原 `NOTIFICATION_ENCRYPTION_KEY`：原通知密文必须使用原密钥解密，恢复时不能随意生成一个新值替代。密钥丢失时，SQL 里的密文无法直接恢复凭据，只能重新配置渠道。
- 新实例的 MySQL / Redis 密码与连接配置：SQL 没有系统账号授权；新库由 Compose 用新的独立密码初始化，应用连接必须与新实例匹配。
- DeepSeek / MCP 配置与站点网址：演练中供应商密钥保持空，调度保持关闭。生产恢复后启用前再核对用途、价格和任务状态。
- 对应 Git 版本、镜像标签 / 标识：先使用与备份 schema 兼容的版本恢复验证，升级迁移另行执行，不回退覆盖已有生产结构。

本轮演练的通知密钥留在独立、忽略的环境文件中，没有打包进 SQL；样例没有真实通知凭据，**未验收真实密文解密或渠道送达**。独立保存当前密钥的步骤必须在生产首次设置时完成。

## 恢复到全新测试项目

准备独立的 `.local/restore.env`，参考 `deploy/.env.example` 填写新的数据库 / Redis 密码，供应商密钥为空，保留对应通知密钥。选择从未用于业务的新项目名，必须以 `creator-agent-restore-` 开头。

先只创建 MySQL，**不要创建 backend 容器**，避免自动重启、Flyway 或通知扫描与导入并行：

```sh
docker compose --env-file .local/restore.env -f deploy/compose.yml -p creator-agent-restore-20261011 up -d --wait mysql
sh deploy/restore.sh creator-agent-restore-20261011 .local/restore.env .local/backups/creator-agent-20261011T151135Z-eOnFbP/database.sql
```

第二行最后的参数替换为备份脚本实际打印的 SQL 路径。恢复检查：项目名、完整文件 / 格式 / 源项目、SHA-256、无后台容器、库内没有任何表；失败即退出，不自动清空或删除目标表。运行、重启、暂停和已停止的后台容器都不允许进入恢复。导入失败可能留下部分表，保留该隔离库排查，后续使用另一个新项目，不能直接重导覆盖。

完成后仍不启动应用。先通过只读 SQL 比对指定用户、资料、分段 / 账号关系、计划、任务、迁移历史与数量。原样比对通过后，如需启动隔离副本，先保存比对证据，再仅在**恢复副本**关闭通知渠道：

```sql
UPDATE notification_settings SET email_enabled = FALSE, feishu_enabled = FALSE;
```

关闭周生成 / 复盘调度、清空模型 / MCP 密钥，并使用全新 Redis。当前通知重试扫描独立于生成调度，不能只靠生成开关防止邮件或飞书发送；渠道关闭才不会领取其待发送记录。启动会核对中断任务，必要时改变其状态，因此先保留原样快照再启动，不将启动后的状态变化误判为 SQL 恢复损坏。

这一脚本仅用于隔离恢复，**不提供覆盖生产库的操作**。测试结束用相同项目名执行 `down`，不加 `--volumes`，保留恢复证据与卷；不要删除源数据或生产卷。

## 已验证与待验证

本地已完成：脚本保护、真实导出 / 导入、31 行关键内容与关系精确比对、UTF-8 中文 / emoji、非空库重复导入拒绝。源 / 目标只运行专用测试 MySQL，开发库未改动，无模型 / 通知调用。

尚待云端：实际备份目录 / 权限 / 空间、备份维护与异机副本、生产独立密钥保存、真实通知密文恢复。详细证据见 [W8-D5 本地恢复验收](qa/2026-10-11-w8-recovery.md)，部署边界见 [部署记录](deployment.md)，状态见 [固定进度页](PROGRESS.md)。
