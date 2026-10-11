# 抖音官方接入条件与项目现状

核对日期：2026-10-11。依据为公开官方开发者文档及本项目代码；未登录用户的开放平台控制台，未提交应用/能力申请，也没有调用抖音接口。

## 当前可用与未接通的部分

| 能力 | 当前实现与证据 | 状态 |
| --- | --- | --- |
| 本人一个/多个内部内容账号 | Cookie 身份、owner/account 隔离；建档不等于抖音授权 | 已实现 |
| 作品累计播放、点赞、评论数 | 本人提供 CSV，预览/确认/重复保护；指标来源分别计算 | 已实现，取决于实际取得的输入 |
| 评论正文及主题证据 | 本人提供 CSV，常见邮箱/手机脱敏，最多 20 条关键词匹配样本 | 已实现，非完整评论自动采集 |
| 手动/每周复盘 | 确定性事实、来源、可能解释与选题实验；共用模型账本/预算 | 已实现；开发库自动扫描关闭 |
| 同行参考 | 导入适用账号的资料/链接，有出处的片段供选题参考 | 已实现；没有完整同行指标 |
| 抖音 OAuth、token 管理、授权账号绑定 | 源码和配置示例中没有对应实现 | 未接入 |
| 抖音作品/评论持续同步 | 没有官方数据客户端或同步任务；已有定时任务生成报告 | 未接入，自动监控尚未验收 |
| 实际运营效果 | 当前留存的 DEMO 数据证明导入与复盘链路，不能证明选题提升效果 | 待实际发布、观测与实验 |

用户是否已经注册真实抖音账号、拥有开放平台应用或能力权限，本轮未核实。不能把“自己拥有账号”推导成“应用可以直接调用播放量或评论接口”。当前继续通过实际取得的 CSV 开发和使用 Agent，无需等官方申请完成。

## 官方申请要求：主体、应用、能力和用户授权分别核对

官方入驻说明区分企业身份和个人身份；当前入驻表为个人列出的可创建类型是小玩法/小游戏/小程序。本人实际可用的应用类型及主体认证结果需在控制台确认，不能仅凭注册开发者账号承诺能创建网站应用或取得数据权限。[官方入驻说明](https://partner.open-douyin.com/docs/resource/zh-CN/developer/join/join-into-developer-platform)

官方创建指引要求先完成开发者入驻；创建出的移动/网站应用最初是测试应用。网站上线流程要求完善应用基础信息、官网链接及符合文档要求的备案主体关系，再上线转正。这里记录平台申请条件，未核实本项目是否已有对应主体、官网或上线资格。[创建应用](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/app-mgmt/create-mobile-and-web-app)、[上线流程](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/app-mgmt/pub-app/)

网站授权文档要求应用通过开放平台审核、取得 ClientKey/ClientSecret、配置回调地址，并经过用户同意授权；服务端以 code 换 access_token 再调用获准的接口。应用认证、能力开通与每个用户授权是不同条件；站内登录 Cookie 不能代替抖音 OAuth token。[网站授权](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/sdk/web-app/web/permission)

官方角色说明指出开发者类型、企业号等对应不同权限，具体能力在应用详情管理。**本次没有获得网站应用可申请的完整视频指标权限清单，也没有控制台审批结果，因此不把旧资料中的 data.external.item/video.list 等 scope 宣称为本项目当前可用能力。** 申请时保存控制台实际能力名称、scope、适用应用类型、审批状态与限制，再做接口验证。[角色与权限](https://partner.open-douyin.com/docs/resource/zh-CN/developer/introduction/type-and-permission)

## 小程序文档中的数据能力：用于分辨接入类型

| 官方文档 | 明确列出的条件与范围 | 对本项目的结论 |
| --- | --- | --- |
| [视频基础数据](https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/basic-abilities/video-id-convert/user-recent-video-data/get-basic-data) | ma.item.data，企业开发者；小程序内申请近 30 天视频数据能力、用户授权，查询 token 对应用户上传的作品。首次授权后的数据及后续刷新存在文档所述延迟 | 这是小程序场景，不能直接作为网站已获权限的证明，也不能承诺所有历史作品实时可查 |
| [评论列表](https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/basic-abilities/video-id-convert/video-review-data/comment-list) | ma.item.comment，能力申请与小程序用户授权；item_id/open_id、cursor/has_more 分页，单页 1～50 条 | “评论总数”与“正文列表”是不同输入；取得正文还要验证对应权限与分页 |
| [小程序授权](https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/api/open-interface/authorization/tt-show-douyin-open-auth) | 已审批 scope 才能授权；需要抖音真机环境，文档标注不支持沙盒 | Web OAuth 与 tt.showDouyinOpenAuth 不互换；这些说明不构成本项目新增小程序的承诺 |

以上是公开文档核对，未执行上述 API。部分动态接口页正文由搜索索引返回，直接页面只展示目录；如控制台、最新文档或实际返回不同，接入前重新核实。社区问答和第三方教程未作为能力获批依据。

## 正式同步前的可验证检查

下列是本项目后续实现/验收要求，不是声称官方已提供的功能：

1. 核实主体及网站应用类型，记录本应用实际审批的数据 scope 和限制；用户完成授权后，将 provider 账号映射到当前 owner/account，回调 state 与站内身份校验不能绕过。
2. 在后端保存/刷新 token，密钥不进入前端、CSV、日志或 Git；验证过期、撤销、缺权限、额度不足均能停下并显示状态。
3. 对照样本明确累计还是每日新增、更新时间还是本机取数时间。当前 CSV 只接受累计观测，不能把日增数据直接填入累计字段；缺失保持未知。
4. 核对公开作品编号与官方转换后的 item_id 映射及长度，不截断标识，不仅凭链接猜测；使用获准的本人账号作品，不推测任意同行播放量。
5. 用真实授权账号的一条作品实际核对播放、点赞、评论计数和正文分页；标注授权范围、数据更新时间、来源及留存范围。
6. 在隔离环境验证重复拉取、来源隔离、失败重试和 owner/account 归属，避免覆盖 CSV 的同来源历史或混入其他用户数据。

仅记录权限/文档不能勾选“官方持续同步已验收”；必须有实际授权及调用证据。腾讯云部署和 Agent 功能迭代继续按计划推进，真实数据和运营效果分别验收。
