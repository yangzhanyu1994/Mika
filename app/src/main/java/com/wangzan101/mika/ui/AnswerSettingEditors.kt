package com.wangzan101.mika.ui

/** One row on 接听设置. Each row opens exactly one editor, and editors do not share fields. */
internal enum class AnswerSettingRow {
    Greeting,
    OutgoingGreeting,
    DefaultSim,
    DefaultBrief,
    Duration,
    Summary,
    StrangerOnly,
    RingTimeout,
    Credentials,
    AudioChannel,
    BackgroundLock,
    AnswerScope,
    AnswerDelay,
    Allowlist,
    SelfAnswer,
}

/** The screen a row opens. [None] is an inline control, not a shared form. */
internal enum class AnswerSettingEditor {
    Greeting,
    OutgoingGreeting,
    DefaultSim,
    DefaultBrief,
    Duration,
    None,
    StrangerOnly,
    RingTimeout,
    Credentials,
    AudioChannel,
    BackgroundLock,
    AnswerScope,
    AnswerDelay,
    Allowlist,
    SelfAnswer,
}

internal fun answerSettingEditor(row: AnswerSettingRow): AnswerSettingEditor = when (row) {
    AnswerSettingRow.Greeting -> AnswerSettingEditor.Greeting
    AnswerSettingRow.OutgoingGreeting -> AnswerSettingEditor.OutgoingGreeting
    AnswerSettingRow.DefaultSim -> AnswerSettingEditor.DefaultSim
    AnswerSettingRow.DefaultBrief -> AnswerSettingEditor.DefaultBrief
    AnswerSettingRow.Duration -> AnswerSettingEditor.Duration
    AnswerSettingRow.Summary -> AnswerSettingEditor.None
    AnswerSettingRow.StrangerOnly -> AnswerSettingEditor.StrangerOnly
    AnswerSettingRow.RingTimeout -> AnswerSettingEditor.RingTimeout
    AnswerSettingRow.Credentials -> AnswerSettingEditor.Credentials
    AnswerSettingRow.AudioChannel -> AnswerSettingEditor.AudioChannel
    AnswerSettingRow.BackgroundLock -> AnswerSettingEditor.BackgroundLock
    AnswerSettingRow.AnswerScope -> AnswerSettingEditor.AnswerScope
    AnswerSettingRow.AnswerDelay -> AnswerSettingEditor.AnswerDelay
    AnswerSettingRow.Allowlist -> AnswerSettingEditor.Allowlist
    AnswerSettingRow.SelfAnswer -> AnswerSettingEditor.SelfAnswer
}

/** Fields the editor is allowed to change. Used to keep editors from overlapping. */
internal fun answerSettingFields(editor: AnswerSettingEditor): Set<String> = when (editor) {
    AnswerSettingEditor.Greeting -> setOf("greeting")
    AnswerSettingEditor.OutgoingGreeting -> setOf("outgoingGreeting")
    AnswerSettingEditor.DefaultSim -> setOf("defaultOutgoingSimId")
    AnswerSettingEditor.DefaultBrief -> setOf("defaultBrief")
    AnswerSettingEditor.Duration -> setOf("maxCallSeconds")
    AnswerSettingEditor.None -> emptySet()
    AnswerSettingEditor.StrangerOnly -> setOf("answerStrangersOnly")
    AnswerSettingEditor.RingTimeout -> setOf("answerOnRingTimeout")
    AnswerSettingEditor.Credentials -> setOf("credentials", "speaker")
    AnswerSettingEditor.AudioChannel -> setOf("audioMode", "audioVerified")
    AnswerSettingEditor.BackgroundLock -> setOf("backgroundStandby")
    AnswerSettingEditor.AnswerScope -> setOf("allowAllIncoming")
    AnswerSettingEditor.AnswerDelay -> setOf("autoAnswerDelaySeconds")
    AnswerSettingEditor.Allowlist -> setOf("allowedIncomingNumbers")
    AnswerSettingEditor.SelfAnswer -> setOf("bypassIncomingNumbers")
}
