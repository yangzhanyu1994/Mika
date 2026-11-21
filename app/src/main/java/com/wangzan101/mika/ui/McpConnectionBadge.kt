package com.wangzan101.mika.ui

import com.wangzan101.mika.settings.McpHealth
import com.wangzan101.mika.settings.McpProbe

/**
 * Sidebar row for one MCP server. A missing or in-flight probe is never "connected".
 * Only a completed successful probe is connected. OAuth failures stay "needs auth".
 */
internal fun mcpConnectionBadge(enabled: Boolean, health: McpHealth?): DrawerBadge = when {
    !enabled -> DrawerBadge.NotEnabled
    health == null || health.probe == McpProbe.Unknown -> DrawerBadge.Disconnected
    health.probe == McpProbe.Checking -> DrawerBadge.Checking
    health.probe == McpProbe.NeedsAuth -> DrawerBadge.NeedsAuth
    health.probe == McpProbe.Connected && health.ok -> DrawerBadge.Connected
    else -> DrawerBadge.Failed
}
