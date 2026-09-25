# W3 内容生成联调记录（2026-09-25）

开发环境：本地 `dev` 用户、独立测试账号、4 份测试资料，使用已配置的 DeepSeek。资料均由开发联调时导入；英语短文是原创测试文本，不代表用户已经提供正式发布材料。

| 案例 | 结果 | 人工核对 |
| --- | --- | --- |
| Java 21 `volatile` 可见性与非原子性 | 选题和脚本保存成功 | 写后读的同步关系符合 [JLS 21 §17.4.4/17.4.5](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html)；`i++` 的复合步骤没有被说成原子操作。 |
| Java 21 `HashMap` null key、顺序和线程安全 | 选题和脚本保存成功 | 核心结论符合 [Java 21 HashMap API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html)。脚本中“用 `get` 确认 null value 能取到”表述不严谨：返回 `null` 不能区分键不存在，正式使用前应改为 `containsKey` 辅助判断。 |
| Java 21 `synchronized` 监视器和互斥 | 选题和脚本保存成功 | 获取、释放同一对象监视器的说明符合 [JLS 21 §14.19](https://docs.oracle.com/javase/specs/jls/se21/html/jls-14.html#jls-14.19)。 |
| 原创英语短文跟读 | 选题和脚本保存成功 | 断句、表达和练习步骤来自导入短文，标记了来源和“仅跟读练习”；没有冒称托福官方评分或其他作品原文。 |

四个案例的 `sourceIds` 都只包含本次提供的资料 ID。原先 Java 输出把 `outline` 写为字符串数组，严格校验拒绝；现在验证器会在长度限制内将字符串数组规范化为文本。真实联调后 4 条选题和 4 条脚本均一次校验成功。独立数据库集成测试验证了草稿按用户读取；另一个用户无法读取这些草稿。

待修：脚本口播时长只由提示词控制，实测 Java 和英语文案均可能超出请求的 45 秒；正式发布前需人工删改或加入时长校验。`HashMap` 演示需使用 `containsKey` 修正。MCP 真实联网搜索仍需 Tavily 凭据，当前生成联调只使用本地资料。

后续将生成链路改为 LangGraph4j 的 `readAccount → retrieveEvidence → generateDraft → validateDraft → saveDraft` 五节点流程；使用 Java 和英语各一条真实内容再次联调，两条选题及脚本均一次成功。数据库迁移 V4.1 为运行记录增加 `failedNode`，集成测试确认失败节点和原因可查询。该运行记录不是可恢复执行的 LangGraph checkpoint；整周草稿入口也尚未完成。
