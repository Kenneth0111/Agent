# 作品指标与评论 CSV 约定

W7-D1/D2 数据约定，2026-10-11。数据库结构、模板及指标上传预览/确认接口已建立；趋势 API 待 W7-D3，导入页面待 W7-D6。第一版从用户实际取得的导出或手工记录导入，持续抖音接口同步另行接入。

模板：[`video-metrics.csv`](templates/video-metrics.csv)、[`video-comments.csv`](templates/video-comments.csv)。使用 UTF-8 CSV（允许 BOM），第一行是固定字段名，字段顺序与模板相同；包含逗号、换行或双引号的文本遵守 CSV 引号规则。模板只有表头，避免示例数据误入真实统计。

## 标识与归属

- `accountId` 是工作台内部内容账号 UUID，可从账号接口取得，不能填写抖音号或昵称。
- `videoId` 是外部作品标识，按字符串保存，1～128 字符，保留前导零；同一内部账号下唯一。与内容日历 `externalWorkId` 一致时可供后续关联。它不表示账号已经获得官方授权。
- `ownerId` 由登录用户决定，CSV 没有 userId/ownerId 字段。上传预览与确认都检查账号归属；数据库通过账号、作品的复合外键限制 owner_id 不匹配的写入。
- `source` 是数据来源标签或来源地址，1～160 字符，区分大小写。同一作品的不同来源保留各自快照，后续趋势按来源分别计算。来源标签是用户提供的信息，不能证明数据已获供应商验证。

## 指标快照

| 字段 | 规则 |
| --- | --- |
| accountId / videoId | 同上，必填 |
| capturedAt | 实际观测时间，必填带时区的 ISO 8601，例如 `2026-10-10T18:00:00+08:00`；存储前转换 UTC，最多 6 位小数秒 |
| views | 累计播放量，空白表示未知；已知值为 0～9223372036854775807 的整数 |
| likes | 累计点赞量，同上 |
| commentCount | 累计评论数，同上；不等于本次导入的评论样本数 |
| source | 必填，同上 |

三个指标至少有一个已知值。`0` 表示实际观测为零，空白/空引号表示未知；`null`、`N/A`、负数、小数和带千位逗号的数字均不当作合法数值。时间、数字错误返回行号，不默默改成零。

快照唯一键为 `(accountId, videoId, capturedAt_UTC, source)`。相同键及相同指标重导视为重复，不新建记录；相同键但任何指标不同（包含空白与零不同）视为冲突，要求用户核对，不能静默覆盖。相邻时间的值下降保留原数据，由趋势展示为校正或删除提示，不强制改成正增长。

## 评论样本

| 字段 | 规则 |
| --- | --- |
| accountId / videoId / source | 同上，必填 |
| commentId | 来源内稳定的评论标识，1～128 字符；没有可用标识时由导入者提供稳定样本编号，重导沿用 |
| text | 必填文本，去除两端空白后 1～2000 字符 |
| publishedAt | 评论发布时间，可留空表示未知；填写时使用带时区 ISO 8601 |
| capturedAt | 样本实际采集时间，必填，规则同指标 |

评论唯一键为 `(accountId, videoId, commentId, source)`。相同键且文本/发布时间一致可跳过重复；内容冲突需显示行号。采集时间可用于溯源，不能冒充发布时间。导入前去除不必要的评论者身份和个人信息；模板及数据库不设置昵称、头像、手机号、评论者 ID 字段。仅导入实际可取得且允许使用的评论文本。

## 数据模型与样例

`video_works` 保存作品标识和归属；`video_metric_snapshots` 保存观测时间、来源与可空指标；`video_comments` 保存评论样本文本及采集时间。两类子记录都绑定作品和相同 owner_id。来源与外部标识大小写分别保存。

[`video-metrics-valid.csv`](samples/video-metrics-valid.csv) 是演示的两次观测及“播放未知、点赞为零”；[`video-comments-demo.csv`](samples/video-comments-demo.csv) 是一条演示评论。示例的内部账号 UUID 不指向真实账号，导入前须替换为当前用户自己的账号；`DEMO_MANUAL` 标明模拟数据，不能作为真实运营验收证据。

[`video-metrics-errors.csv`](samples/video-metrics-errors.csv) 从第 3 行起依次包含相同快照重复、冲突、所有指标未知、负数及错误时间。预览须逐行解释，并在确认前拒绝有错误的文件；相同重复可以报告为跳过，不能偷偷丢弃其他行。

## 指标上传接口

两个接口均使用已登录会话、CSRF header 和 `multipart/form-data` 的 `file` 字段；每个文件最多 1 MiB、1000 条数据记录。字段两端空白在校验前移除，指标空白仍保留为未知。超过限制拒绝整份文件，不截断行。CSV 解析使用固定版本 Apache Commons CSV 1.14.1 的 RFC4180 格式，参考 [Apache 官方 API](https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVParser.html)。

| 接口 | 成功结果 | 写入行为 |
| --- | --- | --- |
| POST `/api/metrics/import/preview` | 200，`canImport` 与 `rows` | 不写入；错误文件返回 `canImport=false` |
| POST `/api/metrics/import/confirm` | 200，`imported`、`duplicates` 与 `rows` | 重新上传同一文件，再查归属与重复键；全部校验通过才按事务写入 |

`rows` 中每项含 `rowNumber`、`status`、`errorCode`、`data`。`rowNumber` 是逻辑 CSV 记录编号（表头为 1，多行引号字段视为一条记录），普通单行 CSV 与文本行号一致；无法可靠拆行的格式/编码错误返回第 1 行文件级错误。状态分别为 `READY`、`DUPLICATE`、`ERROR`；确认成功时新记录为 `IMPORTED`。`data` 保留合法字段和规范化 UTC 时间，错误记录为 `null`。

确认遇到任何错误或冲突返回 422，响应仍含逐行预览，整份文件不写入；超大小返回 413，匿名返回 401，缺失或错误 CSRF 返回 403。若数据库发生并发约束冲突，返回 409 和 `IMPORT_CONFLICT_RETRY_PREVIEW`，由用户重新预览。正常并发导入同账号会按账号顺序加锁串行重校验，重复请求报告跳过，不增加同一快照。

常见逐行错误：`CSV_COLUMNS_INVALID`、`ACCOUNT_ID_INVALID`、`ACCOUNT_NOT_FOUND`、`VIDEO_ID_INVALID`、`CAPTURED_AT_INVALID`、`VIEWS_INVALID`、`LIKES_INVALID`、`COMMENT_COUNT_INVALID`、`METRICS_MISSING`、`SOURCE_INVALID`、`SNAPSHOT_CONFLICT`。文件级错误包括 `CSV_EMPTY`、`CSV_NO_ROWS`、`CSV_HEADER_INVALID`、`CSV_UTF8_INVALID`、`CSV_INVALID`、`CSV_TOO_LARGE`、`CSV_TOO_MANY_ROWS`。空行也作为错误记录处理，不静默跳过。

本接口目前只导入指标；评论模板与结构已准备，评论导入仍属于 W7-D4。上述自动测试使用演示数据，不表示已完成真实运营数据验收。

## 作品与观测趋势读取

登录后，`GET /api/metrics/works?accountId=内部账号UUID` 返回本人账号的作品 `id`、外部 `videoId` 和实际存在的 `sources`。`GET /api/metrics/works/{workId}/trend?source=来源` 返回该作品、该来源的观测序列。其他用户的账号或作品返回 404，匿名返回 401；不同来源（包括大小写不同的来源）分别计算。

趋势包含 `workId`、`source`、`truncated`、`points`。最多取最近 1000 条快照，再按 UTC 观测时间从早到晚排列；超过窗口时 `truncated=true`，页面明确提示。每个点的字段如下：

| 字段 | 含义 |
| --- | --- |
| capturedAt / previousCapturedAt | 当前、窗口内上一次观测时间；首条 previousCapturedAt 为 null |
| views / likes / commentCount | 当前快照累计值，十进制字符串或 null；字符串避免 JavaScript 大整数精度损失 |
| viewsDelta / likesDelta / commentCountDelta | 当前减去相邻上次值，十进制字符串或 null；任一端缺失不计算，也不跨过缺失点补算 |
| likeViewPercent / commentViewPercent | 当前累计点赞/播放、评论/播放的百分数，字符串保留 6 位小数（HALF_UP）；缺少操作数或播放为 0 时 null |
| correction | 任一可计算变化量为负时 true，提示核对来源和累计口径，不将下降解释为新增 |

窗口首条仅显示累计值，变化量始终未知；最近快照缺失的累计指标不沿用更早数据。比值不限定为 0～100，也不宣称为平台互动率；观测间隔不同，变化量不能直接比较增长速度。前端显示本地时间，原始 UTC 时间保留在 time 标签的 datetime/title 中。
