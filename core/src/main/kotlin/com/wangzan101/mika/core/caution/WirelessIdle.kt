package com.wangzan101.mika.core.caution

/**
 * 没有任务时关掉无线调试。原版系统上,普通应用写不了这个开关;
 * 只有已经拿到「修改系统设置」授权时,下次任务才能自己再打开。
 * 拿不到这份授权时,默认不关:不然关了之后要用户自己去开发者选项里打开。
 */
object WirelessIdle {
    const val DEFAULT_GRACE_MS = 60_000L

    /** [stored] 为空时按能不能自己再打开来决定默认值。 */
    fun enabled(stored: Boolean?, canReenable: Boolean): Boolean = stored ?: canReenable

    fun due(enabled: Boolean, taskRunning: Boolean, idleForMs: Long, graceMs: Long = DEFAULT_GRACE_MS): Boolean =
        enabled && !taskRunning && idleForMs >= graceMs
}
