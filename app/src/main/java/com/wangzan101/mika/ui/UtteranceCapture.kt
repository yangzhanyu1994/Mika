package com.wangzan101.mika.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoiceSource

/**
 * The same bottom bar as the home chat: tap to type, hold to talk.
 * A finished sentence (send, or the recognized speech) is handed to [onUtterance].
 * [dockInsets] keeps the bar above the navigation bar and the keyboard. A dialog that already
 * applies those insets passes false so the bar is not padded twice.
 */
@Composable
internal fun UtteranceBar(
    voice: VoiceSource?,
    hints: List<Int>,
    busy: Boolean,
    onUtterance: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPickSpeech: () -> Unit = {},
    dockInsets: Boolean = true,
) {
    val c = MikaTheme.colors
    var text by remember { mutableStateOf("") }
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var fieldFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shown = if (fieldValue.text == text) fieldValue else TextFieldValue(text, TextRange(text.length))
    fun submit(raw: String) {
        val words = raw.trim()
        if (words.isEmpty() || busy) return
        text = ""
        fieldValue = TextFieldValue("")
        focus.clearFocus()
        keyboard?.hide()
        onUtterance(words)
    }
    val vh = rememberVoiceHold(voice, onText = { submit(it) }, onSend = { submit(it) })
    val vState = voiceState(vh)
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val voiceHolding = vh?.holding == true
    val voiceGesture = vh != null && !busy && (!fieldFocused || voiceHolding)
    LaunchedEffect(ime, density, vh != null) {
        if (vh == null) return@LaunchedEffect
        var wasUp = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { up ->
            if (wasUp && !up && fieldFocused) focus.clearFocus()
            wasUp = up
        }
    }
    Column(
        modifier.fillMaxWidth()
            .then(
                if (dockInsets) Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                else Modifier,
            )
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
            .testTag("utterance-bar"),
    ) {
        if (vh != null) VoiceFloat(vh, vState, stringResource(R.string.voice_listening), backdrop = null, reduceMotion = MikaTheme.reduceMotion)
        Box(Modifier.fillMaxWidth()) {
            GlassBar(Modifier.fillMaxWidth().keepsKeyboard()) {
                Box(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    val converting = vState is HoldToTalk.State.Converting
                    if (text.isEmpty() && !converting) {
                        RotatingPlaceholder(
                            hintLines(hints), active = !fieldFocused && !busy,
                            reduceMotion = MikaTheme.reduceMotion, Modifier.fillMaxWidth(),
                        )
                    }
                    BasicTextField(
                        shown,
                        { next ->
                            fieldValue = next
                            if (next.text != text) text = next.text
                        },
                        Modifier.fillMaxWidth().testTag("utterance-field").focusRequester(field)
                            .alpha(if (converting) 0f else 1f)
                            .onFocusChanged { fieldFocused = it.isFocused },
                        enabled = !busy,
                        textStyle = MikaType.Body.copy(color = c.ink, textDirection = TextDirection.Content),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit(text) }),
                    )
                    if (converting) VoiceConverting(Modifier.align(Alignment.CenterStart))
                    if (voiceGesture && vh != null) {
                        Box(Modifier.matchParentSize().holdToTalk(vh) {
                            field.requestFocus()
                            keyboard?.show()
                        })
                    }
                }
                if (fieldFocused && vh != null && !busy) {
                    val hint = stringResource(R.string.voice_hold_to_talk)
                    Box(
                        Modifier.size(MikaSize.IconButton).testTag("utterance-mic").holdToTalk(vh) { vh.toast = hint },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MikaIcons.Mic, hint, Modifier.size(MikaSize.Icon), tint = c.ink)
                    }
                }
                val canSend = text.isNotBlank() && !busy
                Box(
                    Modifier.size(MikaSize.IconButton)
                        .background(if (canSend) c.accent else c.greySoft, CircleShape)
                        .clip(CircleShape)
                        .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) { submit(text) }
                        .testTag("utterance-send"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.ink3, strokeWidth = 2.dp)
                    else Icon(
                        MikaIcons.Up,
                        stringResource(R.string.chat_send),
                        Modifier.size(20.dp),
                        tint = if (canSend) c.onAccent else c.ink3,
                    )
                }
            }
            if (vh != null) VoiceBarCover(vh, vState, MikaTheme.reduceMotion, Modifier.matchParentSize())
        }
        if (vh != null) VoiceSheetHost(vh, onPickSpeech)
    }
}

/** Dialog windows pan by default. Resize so the bar and the buttons stay above the keyboard. */
@Composable
internal fun DialogFollowsIme() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@DisposableEffect onDispose {}
        val previous = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { window.setSoftInputMode(previous) }
    }
}
