package com.wangzan101.mika.ui

import com.wangzan101.mika.wechat.WechatConnectionState

/**
 * Key-configuration row. A saved account is not a connection, and a live channel
 * is not proof a message was delivered. This does not send a WeChat message.
 */
internal enum class WechatDrawerKind {
    Unconfigured,
    Checking,
    Connected,
    NeedsAttention,
    ConfiguredIdle,
}

internal fun wechatDrawerKind(hasAccount: Boolean, enabled: Boolean, state: WechatConnectionState): WechatDrawerKind = when {
    !hasAccount -> WechatDrawerKind.Unconfigured
    state is WechatConnectionState.NeedsBinding -> WechatDrawerKind.NeedsAttention
    state is WechatConnectionState.Connecting -> WechatDrawerKind.Checking
    state is WechatConnectionState.Connected -> WechatDrawerKind.Connected
    state is WechatConnectionState.Failed -> WechatDrawerKind.NeedsAttention
    enabled -> WechatDrawerKind.Checking
    else -> WechatDrawerKind.ConfiguredIdle
}

internal fun wechatDrawerBadge(kind: WechatDrawerKind): DrawerBadge = when (kind) {
    WechatDrawerKind.Unconfigured -> DrawerBadge.NotConfigured
    WechatDrawerKind.Checking -> DrawerBadge.Checking
    WechatDrawerKind.Connected -> DrawerBadge.Connected
    WechatDrawerKind.NeedsAttention -> DrawerBadge.Failed
    WechatDrawerKind.ConfiguredIdle -> DrawerBadge.NotEnabled
}
