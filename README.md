<div align="center">

<img src="docs/media/logo.png" alt="Mika" width="88" />

# Mika

**An AI assistant on your phone: say what you need, and it works your apps and the web until it's done.**

It works on a hidden background screen, so you can keep using your phone.

<a href="https://github.com/wangzan101/Mika/releases/latest"><img src="https://img.shields.io/badge/Download%20APK-Android%2011%2B-2ea043?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" /></a>

<a href="https://github.com/wangzan101/Mika/stargazers"><img src="https://img.shields.io/github/stars/wangzan101/Mika?style=flat&logo=github&label=Star&color=1f6feb" alt="stars" /></a>
<a href="https://github.com/wangzan101/Mika/releases"><img src="https://img.shields.io/github/downloads/wangzan101/Mika/total?label=downloads&color=2ea043" alt="downloads" /></a>
<a href="https://github.com/wangzan101/Mika/releases/latest"><img src="https://img.shields.io/github/v/release/wangzan101/Mika?include_prereleases&label=version" alt="version" /></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" /></a>

<br/>

<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/daily?language=Kotlin" alt="#1 Kotlin Repository of the Day" width="250" height="55" /></a>
<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/weekly?language=Kotlin" alt="#1 Kotlin Repository of the Week" width="250" height="55" /></a>
<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/daily" alt="#5 Repository of the Day" width="250" height="55" /></a>

<br/>

English · [简体中文](docs/readme/README.zh-CN.md) · [繁體中文](docs/readme/README.zh-Hant.md) · [日本語](docs/readme/README.ja.md) · [한국어](docs/readme/README.ko.md) · [Español](docs/readme/README.es.md)

<br/>

<img src="docs/media/wechat-send-side.gif" alt="From one sentence, Mika opens WeChat on its background screen, finds the contact and sends the message" width="640" />

<sub>"Send a message to File Transfer": Mika's chat on the left, each step on its background screen on the right (4× speed; chat list blurred)</sub>

</div>

## 必读

### 近期版本更新说明

本记录整理本仓库从 v0.1.40 到 v0.1.66 的正式发布，按版本倒序排列。日期为 UTC+8。每条记录以该版本相对上一版的主要变化为准；新增功能、界面优化和问题修复分别说明。完整提交与安装包见各版本的发布页面。

自动检查通过不等于所有手机都已实测。特别是 AI 通话、联系人、双卡、定时任务、锁屏及后台运行，仍受设备、权限、网络和系统设置影响。拨号请求提交或电话接通，不代表对方已收到或同意转达内容。

#### [v0.1.66](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.66) · 2026-11-14

- **锁屏运行**：增加默认关闭的本机任务自动亮屏解锁开关，使用本机加密保存的 PIN 或密码；电脑远程解锁仍单独授权。
- **任务收尾**：任务结束后尝试重新锁屏；用户正在使用主屏或无法确认已锁定时发出提醒。解锁期间保留通知，异常中断后也应检查手机状态。
- **验证情况**：自动化测试不能覆盖全部系统锁屏样式；三星设备的解锁、任务运行、取消及重新锁屏需真机验证。

#### [v0.1.65](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.65) · 2026-11-14

- **应用内更新记录**：在“关于应用 → 更新记录”中查看逐版中文说明，覆盖近期版本；联网时从仓库更新说明，离线时显示随应用附带的记录。
- **删除对话**：扩大单条与批量删除确认按钮的可点击区域；单条删除后显示处理结果，失败时恢复列表并提示。
- **验证情况**：网络、长列表显示和删除操作的实际效果仍需真机验证。

#### [v0.1.64](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.64) · 2026-11-14

- **AI 电话指令**：优化普通聊天中“给某人打电话并转达事情”的处理，引导 Mika 使用 AI 电话助理；增加按姓名精确查询联系人的入口。
- **预约 AI 拨号**：增加从聊天创建定时 AI 拨号任务的入口，使用现有预约拨号调度；联系人不明确、时间无效或拨号卡不可用时会提示，而不是自行猜测。
- **验证情况**：自动检查及签名发布通过；语音识别意图、联系人权限、实际接管、定时触发及转达仍需真机验证，不能保证每种说法都能正确识别。

#### [v0.1.63](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.63) · 2026-11-14

- **拨出后说事**：在拨号前准备要转达的第一句话，接通后紧接拨出开场白说明来意；准备失败时不拨号，敏感内容先核对接听者。
- **拨号卡**：已保存的默认卡可用时不重复询问；卡不可用时需要重新选择。
- **AI 接管排查**：系统通话信息暂未就绪时短暂等待，并细化无法安全接管的原因。接通后退出接管的根因尚未确认，需真机验证。

#### [v0.1.62](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.62) · 2026-11-14

- **系统助手唤起语音**：把 Mika 设为 Android 数字助理后，助手入口打开聊天并开始语音识别；识别后由用户点击发送。
- **草稿保护**：已有草稿或附件时保留内容供用户检查。电源键唤起与录音流程仍需按设备实测。

#### [v0.1.61](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.61) · 2026-11-14

- **数字助理入口**：支持在 Android 数字助理设置中选择 Mika，系统调用时打开聊天。此版尚未自动开始录音；自动识别由 v0.1.62 补充。

#### [v0.1.60](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.60) · 2026-11-14

- **文件与任务**：文件库增加类型筛选；定时任务增加执行记录。
- **技能修复**：调整试用技能的执行结果判定，避免失败后错误停用技能。
- **主动读取通知**：加入默认关闭的通知处理能力；需要选择应用、授予系统通知权限，并确认将通知发送给所选模型。通知内容不用于长期联系人或话题学习。

#### [v0.1.59](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.59) · 2026-11-14

- **聊天背景**：将条纹背景和动态曲线覆盖层改为柔和纯色背景，保留玻璃表面、自定义背景与深色模式。

#### [v0.1.58](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.58) · 2026-11-14

- **背景动效**：让默认背景线条、波纹和高光随聊天状态变化；启用系统减少动态效果时停止动画。本版的条纹和动态曲线随后在 v0.1.59 改为纯色背景。

#### [v0.1.57](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.57) · 2026-11-14

- **毛玻璃效果**：默认浅色背景下，聊天输入栏与共享卡片采样并模糊后方背景，使玻璃更通透；深色和自定义背景保留兼容路径。

#### [v0.1.56](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.56) · 2026-11-14

- **液态玻璃视觉调整**：根据实际画面与设计参考的差距，重新调整默认背景、卡片、聊天气泡、输入栏和动态光晕。

#### [v0.1.55](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.55) · 2026-11-13

- **状态光晕**：为待机、思考、执行状态加入不同的蓝紫及暖色光晕，并调整浅色卡片与侧栏透明度；减少动态效果时保持静态。

#### [v0.1.54](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.54) · 2026-11-13

- **思考与消息动效**：在等待回复时显示柔和光点，新消息增加淡入和轻微上浮；遵循系统减少动态效果设置。

#### [v0.1.53](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.53) · 2026-11-13

- **语音输入**：主要聊天和旁聊中，按住说话后松手可自动发送识别成功的内容；保留上滑取消，识别失败不发送。
- **消息动效**：增加短暂的新消息淡入效果。

#### [v0.1.52](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.52) · 2026-11-13

- **界面风格**：更新浅蓝白配色、渐变背景、卡片、圆角、侧栏和主要聊天任务卡，开始统一玻璃风格。

#### [v0.1.51](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.51) · 2026-11-13

- **通话被打断后退出**：对重新准备电话播放音频时短暂返回零字节的情况增加有限重试，并细化音频清理失败的诊断。
- **验证情况**：已有一次用户反馈称通话中打断导致 AI 退出的问题得到解决；不代表所有设备和场景均已验证。

#### [v0.1.50](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.50) · 2026-11-13

- **通话提前退出排查**：通话记录显示结束原因，诊断增加分类与计数；适度调整音频发送队列以缓解短暂网络波动。退出根因当时尚未确认。
- **智能场景位置**：支持获取当前位置及使用系统地点搜索选择位置，并增加进度与失败提示。

#### [v0.1.49](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.49) · 2026-11-13

- **时间识别修复**：来电交代只包含一个时刻时，不再自动扩成全天；需要时要求补全结束时间。
- **预约拨号**：主要聊天确认时显示号码和时间；信息、号卡或权限不完整时不安排。冷启动后等待电话助理恢复就绪。
- **发布流程**：避免重复触发发布工作流。

#### [v0.1.48](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.48) · 2026-11-13

- **智能场景**：加入时间、指定蓝牙、Wi-Fi 和位置范围触发，并在通知中呈现任务结果或未完成原因。
- **预约电话**：AI 拨号任务可指定日期、时间和号卡；重复闹钟只认领一次，超时不自动补拨。来电交代支持生效时间与永久生效。
- **已知问题**：单时刻来电时间存在边界问题，已在 v0.1.49 修正；建议使用更新版本。

#### [v0.1.47](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.47) · 2026-11-13

- **拨出设置**：增加默认拨号卡和独立的拨出开场白，接听开场白继续单独保存；默认卡不可用时不会悄悄换卡。
- **通话任务**：拨出任务不复用接听通用应答，并要求 AI 主动说明来意。后续 v0.1.63 进一步改进了开场后的第一句话。

#### [v0.1.46](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.46) · 2026-11-13

- **通话记录**：修复已处理状态筛选；侧栏 AI 电话助理显示未处理通话提示，标记处理后可清除。
- **版本提示与交代**：有新版本时在设置旁显示提示；已有来电交代可设置为永久生效。

#### [v0.1.45](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.45) · 2026-11-13

- **来电交代管理**：普通聊天支持读取、修改和删除来电交代；重复保存完全相同的生效任务时复用已有记录。
- **交代安全性**：修改和删除核对任务编号及原文；对来电者身份和敏感内容的处理增加约束。实际角色识别仍依赖语音模型。

#### [v0.1.44](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.44) · 2026-11-13

- **聊天创建来电交代**：修复普通聊天无法把明确的来电交代保存到通话任务的问题；保存后反馈结果和有效期，不改变自动接听设置。

#### [v0.1.43](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.43) · 2026-11-13

- **接听设置**：整理重复的设置项，增加默认关闭的“仅 AI 接听陌生号码”和响铃超时转 AI 选项。
- **输入与首页**：改进通话任务、通用应答和定时任务的文字及语音输入；首页增加快捷入口与本机使用习惯建议。

#### [v0.1.42](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.42) · 2026-11-13

- **电话助理**：重构通话中心和接听设置，整理 AI 接管、通话任务、就绪检查与号卡信息。
- **语音与联系人**：支持从语音或文字整理电话任务、语音创建定时任务，以及通过通讯录匹配联系人和选择名单。

#### [v0.1.41](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.41) · 2026-11-13

- **界面整理**：调整侧栏、应用设置和来电助理的分组、名称与入口；增加默认首页选择。

#### [v0.1.40](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.40) · 2026-11-12

- **分支基线**：同步 Mika 0.1.40，建立本仓库的签名 APK 发布流程。后续版本记录以上述基线为起点。


### 2026-11-13

- **日期**：2026-11-13（UTC+8）
- **更新人**：Grok Build via Cursor cloud agent
- **测试结果**：`python3 tools/i18n/translations.py check` 通过。`./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --stacktrace` 通过（5344 tests, 0 failures, 2 skipped；debug APK 与 androidTest 编译成功）。
- **来源**：老板 2026-11-13 design previews
- **状态**：代码已写完，未真机验证

### 2026-11-13 · 0.1.43

- **日期**：2026-11-13（UTC+8）
- **更新人**：Grok Build via Cursor cloud agent
- **测试结果**：`python3 tools/i18n/translations.py check` 通过。`./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` 通过（5352 tests, 0 failures, 2 skipped；debug APK 与 androidTest 编译成功）。
- **来源**：老板 2026-11-13 06:39、06:41、06:43、06:49、06:50、06:55、06:56、06:58、06:59 真机反馈
- **状态**：代码已写完，未真机验证
- **说明**：接听设置每一项只编辑自己，音频通道只留一行。仅 AI 接听陌生号码默认关闭，通讯录联系人由本人接；没有通讯录权限时不打开该开关，也不把联系人当成陌生人。响铃超时转 AI 默认关闭，连续响铃 10 秒无人接听后转入 AI；本人接听和已识别的联系人先由人接，白名单排除不会被超时打破。AI 音色放进实时语音模型配置，显示「温柔女声」这类名称，已保存的音色保留。新建和编辑通话任务、通用应答、定时任务的语音输入改成和主页一样的底部输入栏：点按打字，长按说话，键盘弹起时输入栏上移，保存按钮不被挡住。「默认交代」改名为「通用应答」，副标题不变，已保存内容保留。回到首页时输入框自动聚焦并弹出键盘；空对话在输入框上方靠左显示「创建自动化任务」「创建AI电话业务」「个人助手」。输入框里的灰色提示按入口轮换，聚焦或开始输入后停止。首页去掉写死的四条示例；有本机使用记录时按频率和最近使用生成最多 4 条，点一下填入输入框、不直接发送，没有记录则不显示这张卡片。统计只存在本机，不上传。首页右上角与侧栏重复的附件和新建对话按钮已去掉；未保存草稿仍由侧栏「新对话」留住。

## Get things done with one sentence

<table>
<tr>
<td align="center" width="33%"><img src="docs/media/wechat-group.gif" width="230" alt="Reads through a WeChat group and summarizes today's chat" /><br/><b>"What did the group talk about today?"</b><br/><sub>Scrolls through the chat in the background, then sums it up</sub></td>
<td align="center" width="33%"><img src="docs/media/amazon-cart.gif" width="230" alt="Picks a charger on a shopping site and adds it to the cart" /><br/><b>"A charger under $30, rated 4.5 stars or more"</b><br/><sub>Adds the pick to the cart and stops before checkout</sub></td>
<td align="center" width="33%"><img src="docs/media/xhs-summary.gif" width="230" alt="Summarizes a creator's recent posts" /><br/><b>"What has this creator posted lately?"</b><br/><sub>Opens the app, reads post by post, then sums it up</sub></td>
</tr>
</table>

<details>
<summary>More demos</summary>

<table>
<tr>
<td align="center" width="33%"><img src="docs/media/boss-greet.gif" width="230" alt="Greets recruiters for 3 jobs on a job site" /><br/>Greets recruiters for 3 jobs on a job site</td>
<td align="center" width="33%"><img src="docs/media/google-flights.gif" width="230" alt="Finds the cheapest nonstop flight" /><br/>Finds the cheapest nonstop from San Francisco to New York</td>
<td align="center" width="33%"><img src="docs/media/hacker-news.gif" width="230" alt="Reads out the top 5 stories and their points" /><br/>Reads out the top 5 stories on a news site</td>
</tr>
<tr>
<td align="center"><img src="docs/media/job-greet.jpg" width="230" alt="Finds jobs and greets recruiters" /><br/>Finds jobs and greets recruiters</td>
<td align="center"><img src="docs/media/music-vip.jpg" width="230" alt="Watches ads to unlock music perks" /><br/>Watches ads to unlock music perks</td>
<td align="center"><img src="docs/media/food-order.jpg" width="230" alt="Orders takeout with stacked discounts and asks you before paying" /><br/>Orders takeout with stacked discounts, asks before paying</td>
</tr>
</table>

</details>

<sub>Several demos use apps popular in China: WeChat is the main messaging app there, and Xiaohongshu (RED) is a lifestyle and social platform. Mika isn't tied to them; it works with any app or website on your phone.</sub>

## Why Mika

| | |
|---|---|
| 📱 **Leaves your screen alone** | Opens apps, taps, types and scrolls on a separate background screen while you use your phone as usual; tap the small live view whenever you want to watch |
| 🧩 **Works with any app** | Looks at the screen and finds buttons the way a person does: no app APIs, no per-app integration |
| 🌐 **Call it from any web page** | In the built-in browser, say what you want on the page you're reading and it works right there; touch the page and it stops |
| 🧠 **Gets better with use** | Remembers the preferences you tell it, and turns tasks it finishes into skills so next time is faster and steadier |
| 🔒 **Your data stays on your phone** | There is no Mika server; use your own model API key, or sign in with a ChatGPT, xAI or OpenRouter account |
| 🆓 **Open source and free** | Apache-2.0, all code public |

## Get started in three steps

1. **Install**: download the APK from [Releases](https://github.com/wangzan101/Mika/releases/latest).
2. **Connect a model**: in Settings › Model, pick a provider and enter an API key, or just sign in with an account ([models and sign-in](docs/models.en.md)).
3. **To operate phone apps**: follow the "Set up phone control" wizard in Settings and pair wireless debugging on the phone once ([setup guide](docs/setup-guide.en.md)). Skip this if you only want chat, web and files.

Then try saying: "Set alarms for 9:40 and 10:00 tomorrow morning" or "Check the weather at 8 every morning and tell me only if it will rain".

> 💡 Have a computer handy? Send this to the AI agent on it and it will set everything up for you:<br/>
> `Follow https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.en.md to install Mika on the Android phone connected over USB.`

## What it can do

| | |
|---|---|
| **Operate apps** | Reads long lists and chat histories, taps and types, shares text into apps; hands logins, verification codes and payments to you |
| **Browse the web** | A built-in desktop-class browser that keeps you signed in, reads pages, downloads and uploads; paces itself and hands block pages to you |
| **Your current screen** | Double-tap the floating capsule to send the screen you're looking at: "how should I reply?", "note this down" |
| **Schedules and conditions** | "Remind me at 9 tomorrow", "do this every Friday"; runs on time in the original conversation |
| **Jump in or stop anytime** | Keep talking while a task runs to add or correct things (long-press to withdraw or edit one Mika hasn't picked up yet); after a stop it reports where it got to, and "continue" picks up from there |
| **Hold to talk** | Hold the chat input to speak and edit the words before sending; long-press the floating capsule to speak and release to send. Uses the phone’s built-in speech recognition, or a provider’s speech model if it has none |
| **Files and computing** | A Linux sandbox per conversation runs Python, Node and shell on the files you send (arm64) |
| **From your computer** | AI tools on your computer can hand Mika tasks through CLI + Skill or MCP ([use Mika from a computer](docs/computer.en.md)) |

By default Mika runs in **Ask mode**: it checks with you only before spending money (placing orders, paying) and just does everything else. You can switch to **Autonomous mode** instead. See [Ask and Autonomous](docs/modes.en.md).

For how each of these works and the finer details, see [features in detail](docs/features.en.md).

<details>
<summary>Apps and websites with built-in usage knowledge (anything not listed still works)</summary>

The names are shown as they appear in the knowledge files.

<!-- generated:knowledge:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
| Category | Built in |
|---|---|
| **Apps** | 支付宝, Android 设置, 百家号, 哔哩哔哩, BOSS直聘, Chrome, 得到, 抖音, 飞书, Gmail, 京东, 即刻, 快手, 领英, 美团, 拼多多, QQ邮箱, 淘宝, Telegram, 今日头条, Uber, Uber Eats, 微信, 微博, WhatsApp, X, 闲鱼, 小红书, 知乎 |
| **Websites** | Amazon, 哔哩哔哩, Google Calendar, 豆瓣, 抖音, GitHub, Instagram, Gmail, Google Maps, Telegram, 百度贴吧, 微博, 搜狗微信, WhatsApp, 小红书, 小宇宙, YouTube, 知乎, BOSS直聘 |
| **How-to skills** | 改 Mika 自己的设置, 把内容整理成文字, 处理 PDF 文档, 查清一件事, 消息与邮件, 写技能 |
<!-- generated:knowledge:end -->

</details>

<details>
<summary>Supported model providers</summary>

<!-- generated:providers:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
Presets for DeepSeek, 通义千问, Kimi, 智谱, 智谱 Coding Plan, 硅基流动, 火山方舟, MiniMax, 阶跃星辰, 腾讯混元, 百度千帆, OpenRouter, Requesty, OpenCode Go, Anthropic, Gemini, OpenAI, xAI, Mistral, Groq, Ollama, LM Studio and more; for any other provider pick Custom (自定义), which takes any OpenAI-compatible, Anthropic, Gemini or OpenAI Responses endpoint.
<!-- generated:providers:end -->

You can also skip the key and sign in with a ChatGPT (Plus / Pro), xAI / Grok or OpenRouter account. Steps for adding a model, and what to know about each sign-in, are in [models and sign-in](docs/models.en.md).

</details>

## Which phones

| | Requirement |
|---|---|
| Chat, web, files | Android 11 or later |
| Operating phone apps | Android 12 or later; **Android 14+ recommended** (the background screen has its own focus, so it doesn't get in the way of your typing on the main screen) |
| Linux sandbox | arm64 devices |

Not sure your phone is up to it? Run Settings › Recovery & repair › **Compatibility self-check**: 12 checks in about a minute, and you can [send us the results](https://github.com/wangzan101/Mika/issues/new?template=device-report.yml) in one tap.

## Privacy & safety

- **There is no Mika server**: what you say and what's on your screen go only to the model provider you set up.
- API keys and memories are stored encrypted on the phone; conversations and run records stay on the device; update checks only contact GitHub.
- Operating third-party apps and websites automatically may break their terms of service, so use your own judgment. For anything important, use Ask mode and check the results. See the [disclaimer](docs/disclaimer.en.md).

<details>
<summary><b>FAQ</b></summary>

**Wireless debugging turned itself off?** Android turns it off when you change Wi-Fi or restart. With your permission, Mika turns it back on by itself; if it still can't connect, go back to "Set up phone control" and pair again.

**Can't find wireless debugging on my phone?** Some phone systems leave it out. You can start the phone helper from a computer over USB instead; see the [setup guide](docs/setup-guide.en.md).

**Some screens can't be read or captured?** Pages like payments and password entry block screenshots, so Mika asks you to take over there.

**Can I use a model running on my own computer?** Yes: pick "Custom" as the provider and enter its address on your local network.

**Is the app in English?** Yes. The app UI comes in English, Arabic, Simplified Chinese, Traditional Chinese, Japanese, Korean, Spanish, Portuguese, Russian and Indonesian, follows your system language, and can be switched in Settings › Language. The built-in app and website knowledge is written in Chinese, but that doesn't limit you: the model reads it fine, and you can talk to Mika in English.

**Where do updates come from?** Only from this repository's [Releases](https://github.com/wangzan101/Mika/releases); you can also upgrade in the app from About › Check for updates.

More answers are in the [FAQ](docs/faq.en.md).

</details>

## Community & contributing

- **Docs**: all docs and a suggested reading order are in the [docs index](docs/README.en.md)
- **Community**: join the [X group chat](https://x.com/i/chat/group_join/g2116926829198246025/48B52j5svL); to reach the author on WeChat, scan the QR code in the app under About › Contact the author (mention Mika in your request)
- **Follow the author**: X [@wangzan101](https://x.com/wangzan101)
- **Report a problem**: [open an issue](https://github.com/wangzan101/Mika/issues/new/choose); About › Send feedback in the app can attach diagnostics
- **Contribute**: add usage knowledge for an app or website (plain text, no code needed), or contribute code; see the [contributing guide](.github/CONTRIBUTING.md)

## Star History

<a href="https://star-history.com/#wangzan101/Mika&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&theme=dark&v=20261110-2017" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&v=20261110-2017" width="600" />
  </picture>
</a>

## Contributors

Thanks to everyone who has helped build Mika:

<a href="https://github.com/wangzan101/Mika/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=wangzan101/Mika&max=100&columns=12" alt="Contributors" />
</a>

## License

[Apache-2.0](LICENSE) · [NOTICE](NOTICE) · [Third-party components](licenses/THIRD_PARTY_NOTICES.md)
