package com.wangzan101.mika.about

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/**
 * Mika 对外的地址都在这里。
 * 关于页、反馈、许可仍指向原作者仓库;检查更新和安装包只认 [UPDATE_REPO]。
 * 换更新来源只改 [UPDATE_REPO_SLUG]。
 */
object MikaLinks {
    /** 原作者仓库。关于页的开源仓库、使用说明、反馈问题仍指向这里。 */
    const val REPO = "https://github.com/wangzan101/Mika"
    const val REPO_LABEL = "github.com/wangzan101/Mika"
    const val README = "$REPO#readme"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val X = "https://x.com/wangzan101"
    const val X_HANDLE = "@wangzan101"

    /** X 上的 Mika 交流群:点开即申请加入(不用微信的走这里)。 */
    const val X_GROUP = "https://x.com/i/chat/group_join/g2116926829198246025/48B52j5svL"

    /** 本构建的发布仓库。检查更新、更新记录和安装包只走这里,不跟原作者的 Releases。 */
    private const val UPDATE_REPO_SLUG = "yangzhanyu1994/Mika"
    const val UPDATE_REPO = "https://github.com/$UPDATE_REPO_SLUG"
    const val RELEASES = "$UPDATE_REPO/releases"

    /** 检查更新只问这个仓库的 GitHub 发布页(不登录,每小时有次数限制)。 */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$UPDATE_REPO_SLUG/releases/latest"

    /** 打开 App 时是否自动检查更新。本构建默认关;关于页里仍可手动检查。 */
    const val AUTO_CHECK_UPDATES = false

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、Mika 版本三行,下面留空几段给用户写。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新建问题的地址,标题留空、正文预先填好;参数按 URL 编码(空格写成 %20)。 */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
