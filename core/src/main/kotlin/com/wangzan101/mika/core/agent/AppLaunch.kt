package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 任务开头在本地决定要不要先打开一个应用。
 * 只用启动器能看见的名字和包名，不把整份安装列表交给模型，也不为看不见的包新增权限。
 * 用户这句话点名的应用优先。只有用户明确说过要记住的偏好才会下次直接打开。
 * 成功打开过一次只记成候选，不会因此变成默认。
 */
object AppLaunch {
    data class App(
        val packageName: String,
        val label: String,
        /** null 是主用户里的那一份。分身或工作资料带用户编号。 */
        val userId: Int? = null,
        val userName: String = "",
    )

    data class Preference(
        /** 用户要记住的任务说法。空字符串只表示用过，不能用来自动打开。 */
        val phrase: String,
        val app: App,
        val confirmed: Boolean,
    )

    sealed class Decision {
        data class Open(val app: App, val because: Because) : Decision()
        data class Ambiguous(val label: String, val count: Int) : Decision()
        data class Missing(val name: String) : Decision()
        data object None : Decision()
    }

    enum class Because { Named, Preference }

    data class Effect(
        val decision: Decision,
        val save: Preference? = null,
        val revokePhrase: String? = null,
    )

    data class Attempt(
        val packageName: String,
        val because: Because,
        val opened: Boolean,
        /** 打开之后前台包名就是目标，并且没有系统选择框。 */
        val verified: Boolean,
        val openCalls: Int,
        val freshReads: Int,
        val snapshotId: String?,
        val foreground: String?,
        val error: String?,
        val resolveMs: Long,
        val launchMs: Long,
        val screenReadyMs: Long,
    )

    interface Prefs {
        fun current(): List<Preference>
        fun save(preference: Preference)
        fun removePhrase(phrase: String)
        /** 记一次成功使用。不覆盖已经确认的偏好，也不会让下一次没点名的任务自动打开。 */
        fun noteUsed(app: App)
        fun clear()
    }

    fun effect(goal: String, apps: List<App>, prefs: List<Preference>): Effect {
        val text = goal.trim()
        if (text.isEmpty()) return Effect(Decision.None)
        val forget = forgetPhrase(text, apps)
        if (forget != null) return Effect(Decision.None, revokePhrase = forget)
        val remembered = remember(text, apps)
        val named = namedApps(text, apps)
        val decision = when {
            named.distinctLabels.size > 1 -> Decision.Ambiguous(named.distinctLabels.first(), named.apps.size)
            named.apps.size > 1 -> Decision.Ambiguous(named.apps.first().label, named.apps.size)
            named.apps.size == 1 -> Decision.Open(named.apps.single(), Because.Named)
            named.missing != null -> Decision.Missing(named.missing)
            else -> preference(text, apps, prefs)
        }
        return Effect(decision, save = remembered)
    }

    /**
     * 打开与不看屏幕的准备工作同时进行。读屏一定在打开返回之后。
     * 打开失败、被取消或仍是系统选择框时不算新屏幕就绪。
     */
    suspend fun attempt(
        app: App,
        because: Because,
        tools: ToolDispatcher,
        resolveMs: Long,
        prepare: suspend () -> Unit = {},
        run: suspend (Part.ToolCall) -> ToolExecution,
    ): Attempt = coroutineScope {
        val opening = async { openAndRead(app, because, tools, resolveMs, run) }
        prepare()
        opening.await()
    }

    fun note(attempt: Attempt): String = when {
        attempt.verified ->
            "Opened ${attempt.packageName} before the first model turn. The foreground is that app and a fresh read was taken. " +
                "Do not call phone_list_apps or phone_launch_app for it again. Use only the fresh read."
        attempt.opened ->
            "Tried ${attempt.packageName} once, but the foreground is ${attempt.foreground ?: "unknown"}. " +
                "Do not launch it again and do not tap a screen from before this attempt."
        attempt.error == "cancelled" ->
            "Opening ${attempt.packageName} was cancelled. Do not keep launching it or tap the previous screen."
        else ->
            "Tried ${attempt.packageName} once and it did not open (${attempt.error ?: "unavailable"}). Do not retry this launch."
    }

    fun notice(effect: Effect): String? = when (val decision = effect.decision) {
        is Decision.Ambiguous ->
            "More than one visible app matches this request (${decision.count}). Do not guess a package and do not list every installed app."
        is Decision.Missing ->
            "No launcher-visible app is named ${decision.name}. Do not invent a package and do not list every installed app."
        is Decision.Open -> null
        Decision.None -> null
    }

    fun fingerprint(apps: List<App>): String = apps
        .sortedWith(compareBy({ it.packageName }, { it.userId ?: -1 }, { it.label }))
        .joinToString("|") { app ->
            listOf(app.packageName, app.userId?.toString() ?: "-", app.label, app.userName)
                .joinToString(",") { it.replace("|", " ").replace(",", " ").replace("\n", " ").trim() }
        }

    data class Snapshot(val fingerprint: String, val apps: List<App>, val reused: Boolean)

    /** 磁盘或内存里的上一份只在和这次现场列表完全一致时复用。安装、卸载、改名都会换新。 */
    fun accept(previous: Snapshot?, live: List<App>): Snapshot {
        val fingerprint = fingerprint(live)
        val reused = previous?.fingerprint == fingerprint
        return Snapshot(fingerprint, if (reused) previous.apps else live, reused)
    }

    private suspend fun openAndRead(
        app: App,
        because: Because,
        tools: ToolDispatcher,
        resolveMs: Long,
        run: suspend (Part.ToolCall) -> ToolExecution,
    ): Attempt {
        val started = System.nanoTime()
        val opened = try {
            run(Part.ToolCall("app_launch", "phone_launch_app", openArgs(app).toString()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        }
        val launchMs = (System.nanoTime() - started) / 1_000_000
        val error = (opened.content["error"] as? JsonPrimitive)?.contentOrNull
        val ok = opened.content["ok"] == JsonPrimitive(true)
        val chooser = opened.content["chooser"] != null
        if (!ok || chooser) {
            return Attempt(app.packageName, because, ok && !chooser, false, 1, 0, null, null, if (chooser) "chooser" else error, resolveMs, launchMs, launchMs)
        }
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)
            ?: return Attempt(app.packageName, because, true, false, 1, 0, null, null, "no_screen_read", resolveMs, launchMs, launchMs)
        val readStarted = System.nanoTime()
        val read = tools.executeRead(Part.ToolCall("app_launch_read", dump, "{}"))
        val screenMs = launchMs + (System.nanoTime() - readStarted) / 1_000_000
        val foreground = (read.content["currentApp"] as? JsonPrimitive)?.contentOrNull
        val snapshotId = (read.content["snapshotId"] as? JsonPrimitive)?.contentOrNull
        val verified = foreground == app.packageName && snapshotId?.isNotBlank() == true
        return Attempt(app.packageName, because, true, verified, 1, 1, snapshotId, foreground, if (verified) null else "foreground_mismatch", resolveMs, launchMs, screenMs)
    }

    fun openArgs(app: App): JsonObject = buildJsonObject {
        put("package", app.packageName)
        app.userId?.let { put("user", it) }
    }

    private data class Named(val apps: List<App>, val distinctLabels: List<String>, val missing: String?)

    private fun namedApps(goal: String, apps: List<App>): Named {
        val excluded = excludedLabels(goal, apps)
        val labels = apps.map { it.label }.distinct().filter { norm(it).length >= 2 && norm(it) !in excluded }
        val present = labels.filter { label ->
            norm(label) in norm(goal) && labels.none { other ->
                other != label && norm(other).length > norm(label).length && norm(label) in norm(other) && norm(other) in norm(goal)
            }
        }
        val hits = present.filter { mentioned(goal, it) }
        val matched = apps.filter { norm(it.label) in hits.map(::norm) }
        val narrowed = disambiguate(goal, matched)
        val requested = requestedName(goal)
        val missing = if (hits.isEmpty() && present.size < 2 && requested != null && labels.none { mentioned(goal, it) }) requested else null
        val shown = if (hits.isEmpty() && present.size > 1) present else hits
        return Named(if (hits.isEmpty() && present.size > 1) emptyList() else narrowed, shown, missing)
    }

    /** 只有点名了这个应用才算指定。句子里偶然出现的短词不算。 */
    private fun mentioned(goal: String, label: String): Boolean {
        val name = norm(label)
        if (name.length < 2) return false
        val text = norm(goal)
        if (MARKERS.any { marker -> atMarker(text, marker, name) }) return true
        return text.contains(name + "里") || text.contains(name + "上")
    }

    private fun atMarker(text: String, marker: String, name: String): Boolean {
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(marker + name, from)
            if (at < 0) return false
            val before = text.getOrNull(at - 1)
            val blocked = marker == "用" && (before == '不' || before == '别')
            if (!blocked) return true
            from = at + marker.length
        }
        return false
    }

    private fun preference(goal: String, apps: List<App>, prefs: List<Preference>): Decision {
        val normalized = norm(goal)
        val excluded = excludedPackages(goal, apps)
        val hits = prefs.filter { it.confirmed && norm(it.phrase).length >= 2 && norm(it.phrase) in normalized && it.app.packageName !in excluded }
        if (hits.size != 1) return Decision.None
        val wanted = hits.single().app
        val live = apps.filter { it.packageName == wanted.packageName && it.userId == wanted.userId }
        return if (live.size == 1) Decision.Open(live.single(), Because.Preference) else Decision.Missing(wanted.label)
    }

    private fun remember(goal: String, apps: List<App>): Preference? {
        val rest = strip(goal, REMEMBER) ?: return null
        val match = labelPrefix(rest, apps) ?: return null
        if (match.second.size != 1) return null
        val phrase = rest.drop(match.first.length).trim().removePrefix("来").removePrefix("去").removePrefix("做").removePrefix("for").trim()
        if (norm(phrase).length < 2) return null
        return Preference(phrase, match.second.single(), confirmed = true)
    }

    private fun forgetPhrase(goal: String, apps: List<App>): String? {
        val rest = strip(goal, FORGET) ?: return null
        val match = labelPrefix(rest, apps) ?: return null
        val phrase = rest.drop(match.first.length).trim().removePrefix("来").removePrefix("去").removePrefix("做").trim()
        return phrase.takeIf { norm(it).length >= 2 }
    }

    private fun disambiguate(goal: String, apps: List<App>): List<App> {
        if (apps.size <= 1) return apps
        val text = norm(goal)
        val clones = apps.filter { it.userId != null && !isWork(it) }
        val works = apps.filter { isWork(it) }
        val copies = when {
            "分身" in text || "clone" in text -> if (clones.size == 1) clones else apps
            "工作资料" in text || "workprofile" in text -> if (works.size == 1) works else apps
            else -> apps
        }
        return copies
    }

    private fun isWork(app: App): Boolean {
        val name = norm(app.userName)
        return app.userId != null && ("work" in name || "managed" in name || "工作" in app.userName)
    }

    private fun labelPrefix(rest: String, apps: List<App>): Pair<String, List<App>>? {
        val labels = apps.map { it.label }.distinct().filter { norm(it).length >= 2 }
            .sortedByDescending { norm(it).length }
        val label = labels.firstOrNull { rest.startsWith(it, ignoreCase = true) || norm(rest).startsWith(norm(it)) } ?: return null
        val matched = apps.filter { norm(it.label) == norm(label) }
        return label to disambiguate(rest, matched)
    }

    private fun requestedName(goal: String): String? {
        val match = OPEN.find(goal) ?: return null
        return match.groupValues[1].trim().takeIf { norm(it).length >= 2 }
    }

    private fun excludedLabels(goal: String, apps: List<App>): Set<String> {
        val labels = apps.map { norm(it.label) }.filter { it.length >= 2 }.distinct()
        val captured = NEGATION.findAll(goal).map { norm(it.groupValues[1]) }.toList()
        return labels.filter { label ->
            captured.any { text ->
                text == label || (text.startsWith(label) && labels.none { other -> other.length > label.length && text.startsWith(other) })
            }
        }.toSet()
    }

    private fun excludedPackages(goal: String, apps: List<App>): Set<String> {
        val labels = excludedLabels(goal, apps)
        return apps.filter { norm(it.label) in labels }.map { it.packageName }.toSet()
    }

    private fun strip(goal: String, prefixes: List<String>): String? {
        val trimmed = goal.trim()
        return prefixes.firstNotNullOfOrNull { prefix ->
            if (trimmed.startsWith(prefix)) trimmed.removePrefix(prefix).trim() else null
        }
    }

    private fun norm(value: String) = value.trim().lowercase().replace(WHITESPACE, "")

    private val OPEN = Regex("(?:打开|启动|进入|open|launch)\\s*([^，。,\\s的里和并]{1,24})", RegexOption.IGNORE_CASE)
    private val MARKERS = listOf("打开", "启动", "进入", "使用", "用", "在", "open", "launch", "use")
    private val NEGATION = Regex("(?:不要用|别用|不用|不要打开|don't use|do not use)\\s*([^，。,\\s]{1,24})", RegexOption.IGNORE_CASE)
    private val REMEMBER = listOf("以后都用", "以后用", "默认用", "记住用", "always use")
    private val FORGET = listOf("取消以后用", "取消默认用", "忘掉用", "不要再记住用")
    private val WHITESPACE = Regex("\\s+")
}

/** 偏好记在应用私有文件里。候选和已确认的偏好都只含应用名、包名和用户给的任务短语。 */
class AppLaunchFiles(private val directory: File) : AppLaunch.Prefs {
    private val prefs = File(directory, "app-launch-prefs.txt")
    private val catalog = File(directory, "app-launch-catalog.txt")

    override fun current(): List<AppLaunch.Preference> = synchronized(lock) { read() }

    override fun save(preference: AppLaunch.Preference) = synchronized(lock) {
        val next = read().filterNot { it.confirmed && it.phrase == preference.phrase } + preference
        write(next)
    }

    override fun removePhrase(phrase: String) = synchronized(lock) {
        write(read().filterNot { it.phrase == phrase && phrase.isNotEmpty() })
    }

    fun remove(preference: AppLaunch.Preference) = synchronized(lock) {
        write(read().filterNot {
            it.phrase == preference.phrase && it.confirmed == preference.confirmed &&
                it.app.packageName == preference.app.packageName && it.app.userId == preference.app.userId
        })
    }

    override fun noteUsed(app: AppLaunch.App) = synchronized(lock) {
        val existing = read()
        if (existing.any { !it.confirmed && it.phrase.isEmpty() && it.app.packageName == app.packageName && it.app.userId == app.userId }) return
        write(existing + AppLaunch.Preference("", app, confirmed = false))
    }

    override fun clear() = synchronized(lock) { write(emptyList()) }

    fun load(main: List<Pair<String, String>>, copies: List<Triple<String, Int, String>>): List<AppLaunch.App> {
        val labels = main.toMap()
        val live = main.map { (pkg, label) -> AppLaunch.App(pkg, label) } + copies.mapNotNull { (pkg, user, userName) ->
            labels[pkg]?.let { AppLaunch.App(pkg, it, user, userName) }
        }
        val previous = synchronized(lock) { readCatalog() }
        val accepted = AppLaunch.accept(previous, live)
        if (!accepted.reused) synchronized(lock) { writeCatalog(accepted) }
        return accepted.apps
    }

    private fun read(): List<AppLaunch.Preference> {
        if (!prefs.isFile) return emptyList()
        return prefs.readLines().mapNotNull(::decode)
    }

    private fun write(items: List<AppLaunch.Preference>) {
        directory.mkdirs()
        prefs.writeText(items.joinToString("\n", postfix = "\n") { encode(it) })
    }

    private fun readCatalog(): AppLaunch.Snapshot? {
        if (!catalog.isFile) return null
        val lines = catalog.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val apps = lines.drop(1).mapNotNull(::decodeApp)
        return AppLaunch.Snapshot(lines.first(), apps, reused = false)
    }

    private fun writeCatalog(snapshot: AppLaunch.Snapshot) {
        directory.mkdirs()
        catalog.writeText(buildString {
            append(snapshot.fingerprint)
            snapshot.apps.forEach { append('\n').append(encodeApp(it)) }
            append('\n')
        })
    }

    private fun encode(preference: AppLaunch.Preference): String =
        listOf(if (preference.confirmed) "1" else "0", clean(preference.phrase), encodeApp(preference.app)).joinToString("\t")

    private fun decode(line: String): AppLaunch.Preference? {
        val parts = line.split('\t')
        if (parts.size != 6) return null
        val app = decodeApp(parts.drop(2).joinToString("\t")) ?: return null
        return AppLaunch.Preference(parts[1], app, parts[0] == "1")
    }

    private fun encodeApp(app: AppLaunch.App): String =
        listOf(clean(app.packageName), app.userId?.toString() ?: "-", clean(app.label), clean(app.userName)).joinToString("\t")

    private fun decodeApp(line: String): AppLaunch.App? {
        val parts = line.split('\t')
        if (parts.size != 4 || parts[0].isEmpty()) return null
        return AppLaunch.App(parts[0], parts[2], parts[1].toIntOrNull(), parts[3])
    }

    private fun clean(value: String) = value.replace('\t', ' ').replace('\n', ' ').trim()

    private val lock = Any()
}
