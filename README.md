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

## 必读：项目状态（接手前先看）

**项目目标**

在已发布的 v0.2.23 上加快任务开头的应用选择。目标应用已经确定时，先在本地打开它，再读新屏幕，不再每次先让模型列出应用、再让模型打开。这一轮只实现、测试并开 PR，不合并、不发布。

**当前进度**

- 日期（UTC+8）：2026-11-25 15:59
- 更新者：Grok
- 基线：远端 main `136a6b1d`，已发布 v0.2.23（标签指向合并提交 `c42cec38`，PR 102 已合并）。本轮从该提交新建分支，没有把改动堆到旧的 PR 95 分支。开放 PR 里没有另一份正在做同一范围的应用缓存。
- 代码：本地保存启动器可见的应用名和精确包名；用户明确确认的任务偏好可以记住，这句话点名的应用优先；目标确定后，打开应用与不看屏幕的准备同时进行，实际点按等新屏幕。设置里可以查看和清除偏好。没有新增权限，也没有把全部已装应用交给模型。
- 代码测试：本机 `./gradlew :core:test --offline` 退出码 0，3927 项、0 失败、2 跳过。随后只收紧了缓存指纹里的换行，并重跑 `AppLaunchTest`，退出码 0。`python3 tools/i18n/translations.py check` 退出码 0，3819 条、12 种语言。`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` 退出码 0，应用单测 2733 项、0 失败。
- CI：本记录写入时，这一轮的 PR 检查还没有结果。
- 模拟器：本机没有模拟器，没有跑界面。
- 真机：没有三星 W25。不能把这一版写成已经在真机上变快，也没有未经测量的省时数字。

**重要决定和限制**

- 只缓存启动器查得到的应用。看不见的包保持看不见，不为它们新增权限。
- 分身和工作资料同名、话里又没说明是哪一份时，不猜。
- 只有「以后用某应用做某事」这类明确说法才会记住。成功打开一次只记成候选，下次没点名不会因此打开。
- 购物不指定平台、同名应用、目标没安装、多个目标、不要用某个应用，都不猜。科大物联只是测试里的一个未安装名字，没有写死映射，也不假定已经安装或确认。
- 付款、下单、删除、发消息和修改设置的确认没有改。打开应用不是这些动作的放行。
- 系统设置直达和原来的路线回放还在。已经打开到新屏幕，或这次启动被取消时，不再回放旧屏幕上的点按。
- 分段耗时只记解析、启动、新屏幕就绪和第一次问模型的毫秒数，不记原始指令、日程、坐标、屏幕或密钥。
- 不改签名，不真实外发或购买，不改上游仓库，不自动发布。

**下一步与问题**

等这一轮的 PR 检查。三星 W25 由用户安装后看：缓存冷热、安装卸载和改名、点名覆盖偏好、歧义时不猜、目标没安装、取消后不再点旧屏幕、付款仍要确认，以及侧键语音、应用内语音、免提和悬浮菜单仍可用。

### 2026-11-25 14:19（UTC+8）公开设置页直达

- **日期（UTC+8）**：2026-11-25
- **更新人**：Grok Build
- **测试结果**：本机 `./gradlew :core:test --tests com.wangzan101.mika.core.agent.DirectEntryTest` 退出码 0，6 项、0 失败。测到打开无线网络与打开蓝牙不共用同一次跳转，打开调用不带 snapshotId 或 ref，解析失败只试一次且不再读屏，前台不是系统设置时只重新读一次屏。次数是这条路径自己的计数（0 次模型、1 次打开、成功或对不上时 1 次事后读屏），不是真机耗时，也不承诺加速百分比。`FeatureFlagsSpeedTest` 与 README 译文检查退出码 0。PR 检查 https://github.com/yangzhanyu1994/Mika/actions/runs/45561199971 四个作业成功。签名发布 https://github.com/yangzhanyu1994/Mika/actions/runs/45562083791 成功。本机下载两个安装包后，SHA-256 与 GitHub 摘要一致，证书 SHA-256 `5229097bf794dfee578cf40cbbf944199f628cf8bc9059fec4f69270c234fbc6`，与 v0.2.22 相同，包名 `com.wangzan101.mika.fork`，versionName 0.2.23，versionCode 2023，高于 2022，可覆盖安装。真机未验证。
- **来源**：用户 2026-11-25 14:19（UTC+8）授权，基于当时已发布的 v0.2.22（`955aac05`，版本号 0.2.22）做一版可安装的手机操作提速。不合并上游 v0.1.51，不做教 Mika，不改发送、购买、删除的原确认。

直达只使用已经列在 `LinkPolicy` 里的公开 `android.settings.*` 动作。开发者选项里原有的「直接打开要去的页面」这次默认打开，可以关掉，关掉后不再抢先跳页。话里带发送、付款、购买、删除、密码或验证码，或点了微信这类没有核对过的应用，不会抢先跳。跳完或对不上都不再用跳之前的编号。常用路径仍走原来的路线回放：每步重新读屏，对不上就停。界面地图没有另做一套。

回退：设置 → 开发者选项 → 关掉「直接打开要去的页面」。已经关掉的不会被下一次启动重新打开。

安装页 https://github.com/yangzhanyu1994/Mika/releases/tag/v0.2.23 。通用包 https://github.com/yangzhanyu1994/Mika/releases/download/v0.2.23/Mika-v0.2.23.apk ，132,897,616 字节，SHA-256 `26b7dfe6f3537441ec1a659c260e769874a44a06e5f39358101609678b141605`。ARM64 包 https://github.com/yangzhanyu1994/Mika/releases/download/v0.2.23/Mika-v0.2.23-arm64.apk ，120,301,473 字节，SHA-256 `96ebc12b10f06752e5e2eecb09184426a8fba4a2ba45b5e45a2317ee4efe5a53`。标签 v0.2.23 指向合并提交 `c42cec38`，这次记录不移动该标签。

### 项目状态（2026-11-22 23:21 UTC+8）

**项目目标**

发布 v0.2.22，让用户在三星 W25 / SM-W9025 / Android 16 上覆盖安装 v0.2.21。这一版只包含 PR 101：教模型把已知的连续手机步骤放进一次 do_steps 调用。改的是 parallel_call_skipped 那条反馈——原来它只教模型“一次只做一步、看完结果再试”，模型想并行就被推回单步；现在它明确说只有第一步被执行、不要一次发多个手机调用、已知的几步放进一次 do_steps 再看结果。执行器没动、没有 Kotlin 改动。batch.md 的教导和上游一字不差，不用改。开关不用改：v2 默认升级（FeatureFlags.kt:385）早在 2026-11-18 进 fork，每次任务开始都会应用，batchSteps 默认就是开的，除非用户在开发者选项里手动关过——这一点代码里看不出来，真机上以开发者选项“Do several steps when sure”开关为准。真机还没验：固定流程“点搜索框 → 输入 → 点搜索”跑 5 次，对比单步版总耗时与成功率。

**当前进度**

- 日期（UTC+8）：2026-11-22 23:21
- 更新者：小美（这个项目不用逐项问授权，全部允许，直接推进——用户 2026-11-21 18:13 亲口说的；2026-11-22 晚用户定提速顺序：先做 1 再做 2，不并行）
- 代码：PR https://github.com/yangzhanyu1994/Mika/pull/101 已合并。合并提交 `e2059406f2664bccc2c74d59e6267063222e6af3`，PR head `147c443ae79f98a4629d88f2f8bc5ca53a0352a4`。只改了 `core/src/main/resources/prompts/feedback/loop.md` 里 parallel_call_skipped 一条反馈（+157 字节），并同步了两份提示词金样 `core/src/test/resources/prompt-surface/feedback-loop.txt`、`sizes.txt`；没有 Kotlin 改动、不动执行器。do_each + screenmap（优化 2）这轮没动，按用户定的顺序下一轮再做。
- 版本：v0.2.22 已发布，versionCode 2022，高于 v0.2.21 的 2021。包名仍是 com.wangzan101.mika.fork。安装页 https://github.com/yangzhanyu1994/Mika/releases/tag/v0.2.22 。通用包 https://github.com/yangzhanyu1994/Mika/releases/download/v0.2.22/Mika-v0.2.22.apk ，132,883,112 字节，本机下载后 SHA-256 966178abb3f3f7400d2d7d1b339d136e45a5565f22a72004f6b87a77f0b762a7。ARM64 包 https://github.com/yangzhanyu1994/Mika/releases/download/v0.2.22/Mika-v0.2.22-arm64.apk ，120,286,969 字节，本机下载后 SHA-256 4510f6415ef0d4eb0e272c5c856778f8d90cf57892de22df5f220af8b451a6c8。两包的包名、versionName 0.2.22、versionCode 2022、minSdk 30、targetSdk 35、证书 SHA-256 都是本机用 androguard 4.1.4 实查的：证书 5229097bf794dfee578cf40cbbf944199f628cf8bc9059fec4f69270c234fbc6，与 v0.2.18/v0.2.19/v0.2.20/v0.2.21 相同，证书主体是 O=yangzhanyu1994, CN=mika-fork，可覆盖安装。（注：ARM64 包第一次下载时 /tmp 写满被截断在 11,833,344 字节，已改到 workspace 重下完整包再验；通用包不受影响。）
- CI：PR 检查 https://github.com/yangzhanyu1994/Mika/actions/runs/45034555059 四个作业都成功。签名发布 https://github.com/yangzhanyu1994/Mika/actions/runs/45036023672 成功，完成时间 2026-11-22 23:11:25 UTC+8，只推了一次 release/v0.2.22。这次 README 只推 main，不再触发发布。
- 本地代码测试：本机没有 Android SDK，本轮没有跑 gradle 测试；以 PR 检查和签名发布 workflow 的结果为准。
- 模拟器：发布 workflow 在 Android 16 模拟器上只跑了既有的任务启动和通话设备测试。本机没有 emulator。没有本轮 do_steps 教导的模拟器截图。不能把批量调用写成模拟器已验收。
- 真机：未验证。等用户装 v0.2.22 后跑固定流程“点搜索框 → 输入 → 点搜索”5 次，对比单步版总耗时与成功率，确认模型对已知连续步骤发一次 do_steps。本记录不声称已在真机上确认批量调用。
- 本轮 README 正文更新同步改了五份译文的 `translated-from` 标记和中文译文的 v0.2.22 记录；以后每次 README 正文更新都要同步改标记。

**重要决定和限制**

- v0.2.20 真机问题：用户 2026-11-21 19:17（UTC+8）说点不开浮窗，19:15、19:22 两次闪退（系统问题历史记录均为“未知”），重启后点胶囊仍无任何反应；设置里点按动作确认是“展开快捷菜单”，排除设置问题；19:24 附视频。v0.2.19 同一台手机实测菜单能正常打开（仅飞出前 0.5 秒底部两颗芯片短暂重叠），所以问题出在 v0.2.20 这一版改的共用飞行时钟上——四个芯片共用 presenter 持有的一个 `Animatable`、由 presenter 的协程驱动，替代了原来每颗芯片自己的 `Animatable` + `LaunchedEffect`。静态检查没找到确切的崩溃点（OverlayHost.open 本身有兜底、加不上窗口只返回 null），先回滚到已验证能用的行为，再拿真机日志慢慢定位。
- 回滚的代价：飞出前半秒底部两颗芯片短暂重叠的问题会回来，那是 v0.2.20 想修的毛病——但只是外观上的短暂重叠，菜单本身能用。
- 弧形菜单的布局（`CapsuleArc.layout`）没有改：四个按钮仍按四分之一弧线排，按钮 44dp、间距 12dp。
- 没有定位授权就不读取手机位置。指定城市的天气不取定位。当前位置天气按这一次把坐标只发给 Open-Meteo。坐标和日程标题不写入运行记录。
- 不整合上游剩余冲突，不写 wangzan101/Mika，不碰 DOVE、Tailscale、Aqara、真实账号或真实外发消息，不读、不打印密钥。

**下一步与问题**

安装包已经可下载，可覆盖 v0.2.21。三星真机仍由用户看。

- 测试结果：代码已合并。PR CI 通过。签名发布 CI 通过，含 Android 16 上的既有冒烟测试。本轮 do_steps 教导没有模拟器界面证据。真机未验证。
- 来源：用户 2026-11-22 晚定提速顺序先做 1 再做 2。当时未被占用的下一版本是 v0.2.22。
- 真机还要看：固定流程“点搜索框 → 输入 → 点搜索”跑 5 次，模型是不是一次发 do_steps、总耗时和成功率对比单步版；点胶囊球菜单能不能打开；四个芯片落位后位置是否和 v0.2.19 一致；收起是否沿同一路径回去；覆盖安装后旧版本数据是否还在。
- 还要拿真机崩溃日志，定位 v0.2.20 共用飞行时钟为什么会让菜单打不开，再重新修飞出重叠。

### 2026-11-21 07:37（UTC+8）六项批准变更

- **日期（UTC+8）**：2026-11-21
- **更新人**：Cursor Agent（yang 授权）
- **测试结果**：本地 `./gradlew :app:testDebugUnitTest --tests com.wangzan101.mika.ui.ApprovedFollowUpTest --tests com.wangzan101.mika.ui.TaskOrbMotionTest --tests com.wangzan101.mika.ui.LicensesTest` 通过（代码完成）。翻译资源检查通过。模拟器通话页、状态切换、弧形菜单、通知定时和 MCP 侧栏，以及三星真机，在写入本节时尚未完成；不能写成实机通过。发布安装包由 GitHub Actions 用既有 fork 签名构建，本记录不把 CI 通过写成三星外屏已校准。
- **来源**：用户 2026-11-21 07:34（UTC+8）批准的六项，以及 07:37（UTC+8）「改好后由 GitHub CI 签名发布、由用户在手机上测试」的授权。视觉参考是随请求附上的通话页与右侧胶囊弧形菜单。球体几何来自 https://github.com/Jakubantalik/thinking-orbs （MIT，© 2026 Jakub Antalik）。基线是当时的 `main` / 正式版 v0.2.15（`3c4c3d67`，2026-11-21 00:06 UTC+8）。

新行为：

1. 通话页把挂断按钮放在导航/手势条上方约 2 cm（160 dp/英寸）。矮屏或大字号时缩小这段距离，保证按钮仍在屏内。标题与左侧收起、右侧音频图标同一行，并比状态栏再低一截。物理距离仍待三星外屏校准。
2. thinking-orbs 的九种效果分别对应现有状态，不轮播、不新增状态：聆听/说话 = wave（说话跟扬声器电平），接通中 = web，思考 = rubik，搜索 = globe，观察 = morph，执行 = orbits，书写 = ribbon，等待 = braid，空闲 = ring，完成 = 停住的 ring。通话页加宽蓝青亮区，仍是空心点阵。动画只在界面 STARTED 时跑。
3. 「启动时自动刷新」只在设置 → MCP 连接器。默认开启，不挡住启动。侧栏仍用原来的行，探测完成前不显示已连接。需授权和失败分开。测试全部还在。
4. 上午/下午/全天总结各自开关，可改每日提醒时间和起止范围。结束时间不晚于开始时间时，范围从前一天开始，否则是当天。采集沿用通知监听，去重后在本机排序；验证码不进日志、不进保存正文。汇总只在本机通知里展示，不发给模型，也不变成操作。闹钟与任务闹钟分开；强杀或厂商省电不能保证唤醒。合成测试标题带「[合成测试]」。
5. 关键配置增加微信一行，样式与其他状态行相同。未配置、检查中、已连接、失败需处理、已配置但未开启分开。已连接只表示通道在轮询，不表示消息已送达，也不发测试消息。
6. 胶囊点按默认展开四个真实操作（新建会话、语音聊天、截屏提问、隐藏助手），靠右时向左弧线展开、标签在按钮左侧，再点沿原路径收回。设置里可以改回原来的快捷窗口。
7. 设置里增加位置。问「我在哪里 / 当前位置」时，助手调用系统 LocationManager（不依赖 GMS）读取一次定位，回答坐标、精度、采集时间和是否缓存。系统地理编码可用才给地点名，否则只给坐标。无权限、定位关闭、后台未授权、自动定位关闭、没有新定位，分别说明，不用过期缓存冒充实时。自动定位默认关闭；打开后，后台任务在系统「始终允许」已授予时可以读取一次位置，不新建地理围栏，也不全天监听。已有的位置场景仍按原规则。坐标不写入运行记录。定位授权不表示可以把位置外发给别的服务。三星 Android 16 未真机验证。

- **测试结果（含第 7 项）**：位置策略单测覆盖权限、定位关闭、后台授权、自动定位关闭、新鲜读数、缓存和过期缓存。模拟器界面与三星真机仍未验证，不能写成实机通过。

### 近期版本更新说明

本记录整理本仓库从 v0.1.40 到 v0.1.69 的正式发布，按版本倒序排列。日期为 UTC+8。每条记录以该版本相对上一版的主要变化为准；新增功能、界面优化和问题修复分别说明。完整提交与安装包见各版本的发布页面。

自动检查通过不等于所有手机都已实测。特别是 AI 通话、联系人、双卡、定时任务、锁屏及后台运行，仍受设备、权限、网络和系统设置影响。拨号请求提交或电话接通，不代表对方已收到或同意转达内容。

#### [v0.1.69](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.69) · 2026-11-17

- **微信结果推送**：本机任务明确要求发送到本人微信时，可通过已绑定的微信私聊渠道发送结果，无需使用微信 App 自动操作。
- **发送状态**：区分微信接口接受、失败和结果不确定；结果不确定时不自动重发。账号换绑或渠道不可用时不会沿用旧任务的发送对象。
- **使用范围**：需要已启用并绑定本人微信私聊及可用会话令牌；此功能暂不支持定时任务或发送给其他联系人。
- **验证情况**：自动检查通过；实际微信收件、渠道限制及手机体验仍需真机验证。接口接受不代表已读。

#### [v0.1.68](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.68) · 2026-11-16

- **界面与主题**：公共卡片、输入栏和更新弹层改用实色；浅色页面使用系统配色，主题选择只影响用户聊天气泡，支持自定义背景。
- **锁屏凭据**：自动解锁因安全保护暂停后，手动解锁手机并点击恢复，可继续使用已保存的凭据，不必重新输入；保留失败保护。
- **电话后台待命**：未就绪时可进入设置并显示部分原因，条件满足后可启动后台待命。
- **验证情况**：自动检查通过；三星真机仍待验证。锁屏反复失败的具体原因尚未确认，完整测试流程和故障诊断仍待完善。

#### [v0.1.67](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.67) · 2026-11-14

- **系统配色**：浅色模式可跟随 Android 12 及以上系统的动态颜色，背景、卡片和控件一起变化；较低版本使用默认配色。
- **主题颜色**：提供蓝、紫、粉、青、暖杏、靛蓝和石墨色，浅色页面与气泡、按钮协调配色，并在设置中即时预览。
- **兼容性**：保留深浅模式、已保存的颜色选择和自定义背景；系统配色及不同设备的显示效果仍需实机验证。

#### [v0.1.66](https://github.com/yangzhanyu1994/Mika/releases/tag/v0.1.66) · 2026-11-14

- **锁屏运行**：增加默认关闭的本机任务自动亮屏解锁开关，使用本机加密保存的 PIN 或密码；电脑远程解锁仍单独授权。
- **任务收尾**：任务结束后尝试重新锁屏；用户正在使用主屏或无法确认已锁定时发出提醒。解锁期间保留通知，异常中断后也应检查手机状态。
- **验证情况**：自动化测试不能覆盖全部系统锁屏样式；用户反馈锁屏运行和任务后自动锁屏可用；取消及其他锁屏样式仍需验证。

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
