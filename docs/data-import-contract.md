# 作品指标与评论 CSV 约定

W7 数据约定，2026-10-11。数据库、模板、指标/评论上传预览与确认、趋势 API、数据页面和复盘已经建立。第一版从用户实际取得的导出或手工记录导入；[官方接入现状](douyin-access-status.md)单独记录持续同步的申请条件与未验收范围。

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

评论唯一键为 `(accountId, videoId, commentId, source)`。相同键且脱敏后文本/发布时间一致跳过重复；内容冲突需显示记录编号。重导仅采集时间不同也视为重复，保留首次写入的采集时间，不覆盖为本次时间。采集时间可用于溯源，不能冒充发布时间。导入前去除不必要的评论者身份和个人信息；模板及数据库不设置昵称、头像、手机号、评论者 ID 字段。仅导入实际可取得且允许使用的评论文本。

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

上述两个接口只导入指标；评论使用下述独立入口。自动测试使用演示数据，不表示已完成真实运营数据验收。

## 评论上传接口

`POST /api/metrics/import/comments/preview` 与 `/comments/confirm` 使用同一会话、CSRF、multipart `file`、1 MiB 和 1000 条记录限制。表头必须依次为 `accountId,videoId,commentId,text,publishedAt,capturedAt,source`；不接收额外作者字段。UTF-8/BOM、引号内换行与逗号均支持；未知发布时间留空，采集时间必填，时间转为 UTC，最多 6 位小数秒。

评论原文去除两端空白后为 1～2000 个 Unicode 字符，拒绝 NUL。正文中的常见 ASCII 邮箱和连续数字的中国大陆手机号（含可选 86/+86 前缀）分别替换为 `[邮箱]`、`[手机号]`。预览、存储与检索只保留替换后的正文；逐行 `redacted=true` 表示本次有替换。此规则不识别人名、地址、微信号、其它地区号码或分隔写法，这些无关标识由导入者提前移除。外部作品/评论标识需使用稳定编号，不使用个人联系方式。

响应同样包含 `canImport` 或 `imported/duplicates` 及 `rows`。评论 `data` 含规范化字段；错误行 `data=null`，不回显原始正文。正文或发布时间冲突代码为 `COMMENT_CONFLICT`，非法正文、评论标识、发布时间分别为 `COMMENT_TEXT_INVALID`、`COMMENT_ID_INVALID`、`PUBLISHED_AT_INVALID`；公共字段/文件错误沿用指标导入代码。预览不写入，确认重新检查归属与重复，任何错误整份返回 422、不部分写入。评论和指标确认共用账号锁，并发创建同一作品复用同一记录。

[`video-comments-errors.csv`](samples/video-comments-errors.csv) 演示重复、冲突、缺失正文与坏时间；内部账号 UUID 需替换为本人的账号，不能作为真实运营证据。

## 评论与参考资料的主题证据

`GET /api/metrics/review-evidence?accountId=内部账号UUID&q=关键词` 返回本人账号的主题证据。查询为 1～100 字，按字面子串匹配，不把 `%`、`_` 等视作通配符；没有语义分类或情绪分析。无权账号返回 404、匿名返回 401、非法查询返回 400。

| 字段 | 含义 |
| --- | --- |
| accountId / topic | 已验证的账号与去除两端空白后的查询 |
| commentStatus | `MATCHED` 有匹配样本；`NO_COMMENT_SAMPLES` 尚无样本；`NO_TOPIC_MATCH` 有样本但未匹配，不能据此判断需求不存在 |
| commentBasis | 确定性的证据说明，匹配时引用真实 `comment:内部UUID`，不宣称代表全部观众 |
| comments / commentsTruncated | 按采集时间从新到旧的最多 20 条匹配样本；更多匹配时标记 true |
| referenceMaterials | 复用现有资料检索的 status/results，保留资料 ID、原文片段、标题及 sourceUrl/fileName/kind |

每条评论包含 `citationId`、`workId`、`videoId`、脱敏 `text`、可空 `publishedAt`、`capturedAt`、`source`；不返回作者信息或外部评论标识。参考资料只涵盖本人可见且适用于该账号的用户导入材料，可包含同行参考；其中数字是原材料的陈述，不是已核实的平台指标。缺失的同行指标不推测或补零，也不宣称取得完整同行监控。模型复盘在 W7-D5 接入上述证据，数据页在 W7-D6 集成。

`capturedAt` 是实际观测这组累计值/评论样本的时间，不冒充官方平台更新时间。`commentCount` 是观测到的累计评论数，不等于导入样本数量；关键词匹配数量也不能表示全部观众需求。来源字符串由导入者提供，不能单独证明数据真实。完整口径与本周证据见 [W7 验收](qa/2026-10-11-w7-acceptance.md)。

## 作品与观测趋势读取

工作台“作品观测”支持指标/评论文件选择、空白模板下载、服务端逐条预览与确认。页面要求每行 accountId 等于当前选中的内部账号；其他本人账号也须先切换再导入，后端批量接口仍支持多账号文件。改文件、类型或账号会清除旧预览；确认重新验证，422 的最新错误记录在页面显示。网络结果不明时重新预览，已成功保存的相同记录按重复跳过。

指标导入预览及确认响应中的 `data.views/likes/commentCount` 为十进制字符串或 null，避免浏览器在读取 JSON 时舍入 BIGINT；CSV 输入字段仍是非负整数文本。评论预览只展示服务端脱敏内容，以纯文本渲染。

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
