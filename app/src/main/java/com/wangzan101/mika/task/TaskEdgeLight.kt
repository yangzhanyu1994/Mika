package com.wangzan101.mika.task

import android.view.Gravity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.edgeGlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** 主屏边缘仅显示状态，由系统把触摸和按键交给当前 App。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object TaskEdgeLight {
    private const val SUCCESS_HOLD_MS = 550L
    private const val SUCCESS_FADE_MS = 850

    fun start(
        scope: CoroutineScope,
        tasks: TaskCenterState,
        mikaVisible: StateFlow<Boolean>,
        locked: StateFlow<Boolean>,
        quit: Flow<Boolean>,
    ) {
        val running = tasks.runs.flatMapLatest { runs ->
            val states = runs.filterNot { it.conditionCheck }.map { run ->
                run.state.map { it is TaskState.Running }.distinctUntilChanged()
            }
            if (states.isEmpty()) flowOf(false) else combine(states) { it.any { active -> active } }
        }.distinctUntilChanged()
        val success = tasks.edgeLightSuccesses.transformLatest<String, String?> { runKey ->
            emit(runKey)
            delay(SUCCESS_HOLD_MS + SUCCESS_FADE_MS)
            emit(null)
        }.onStart { emit(null) }

        scope.launch(Dispatchers.Main) {
            combine(mikaVisible, locked, quit, ScreenAccessibility.instance) { visible, isLocked, exited, service ->
                service?.takeUnless { visible || isLocked || exited }
            }.distinctUntilChanged().collectLatest { service ->
                if (service == null) return@collectLatest
                var window: OverlayWindow? = null
                val successKey = mutableStateOf<String?>(null)
                try {
                    combine(running, success) { active, completed -> active to completed }.collect { (active, completed) ->
                        successKey.value = completed
                        if (!active && completed == null) {
                            window?.close()
                            window = null
                        } else if (window == null) {
                            window = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) {
                                Edge(successKey.value)
                            }?.also { it.setFullScreen(true) }
                        }
                    }
                } finally {
                    window?.close()
                }
            }
        }
    }

    @Composable
    private fun Edge(successKey: String?) {
        val success = successKey != null
        val fade = remember(successKey) { Animatable(1f) }
        LaunchedEffect(successKey) {
            if (success) {
                delay(SUCCESS_HOLD_MS)
                fade.animateTo(0f, tween(SUCCESS_FADE_MS))
            }
        }
        Box(
            Modifier.fillMaxSize().edgeGlow(
                color = if (success) Color(0xFF22E78C) else Color(0xFF387DFF),
                highlight = if (success) Color(0xFFB5FFD3) else Color(0xFFB0EEFF),
                reducedMotion = animationsOff(LocalContext.current),
                intensity = { fade.value },
            ),
        )
    }
}
