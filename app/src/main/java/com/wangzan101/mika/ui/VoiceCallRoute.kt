package com.wangzan101.mika.ui

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class VoiceCallProgress(
    val motion: OrbMotion,
    val connector: String? = null,
    val needsTaskView: Boolean = false,
)

/** Follows the exact request sent through the normal conversation router. */
internal class VoiceCallRoute(
    private val app: MikaApp,
    private val scope: CoroutineScope,
    private val progress: (VoiceCallProgress) -> Unit,
) {
    private sealed interface Destination {
        data class Research(val turn: Turn) : Destination
        data class Work(val conversation: String, val startedAt: Long) : Destination
    }

    private var researchId: String? = null
    private val destination = CompletableDeferred<Destination>()
    val active: Boolean get() = scope.isActive

    fun startedResearch(id: String) {
        researchId = id
    }

    fun finishedResearch(turn: Turn) {
        destination.complete(Destination.Research(turn))
    }

    fun startedWork(conversation: String, exchangeIndex: Int) {
        val exchange = app.chat.ui.value.exchanges[exchangeIndex]
        destination.complete(Destination.Work(conversation, exchange.startedAt))
    }

    /**
     * Stops work that this call already started. Cancellation does not undo a side effect that already happened.
     * Research that never reached a tool is left as-is because it has no device effect.
     */
    fun cancelStartedWork(words: String) {
        val run = app.taskState.runs.value.firstOrNull { candidate ->
            candidate.goal == words && candidate.state.value.let { state ->
                state !is TaskState.Succeeded && state !is TaskState.Failed &&
                    state != TaskState.Cancelled && state != TaskState.Idle
            }
        } ?: return
        run.cancel()
    }

    suspend fun awaitResult(words: String): String = coroutineScope {
        val research = researchId?.let { id ->
            launch {
                app.researchChat.pending.collect { drafts ->
                    drafts[id]?.let { progress(VoiceCallProgress(it.phase.orbMotion)) }
                }
            }
        }
        val accepted = try {
            destination.await()
        } finally {
            research?.cancel()
        }
        when (accepted) {
            is Destination.Research -> {
                progress(VoiceCallProgress(if (accepted.turn.status == "succeeded") OrbMotion.Complete else OrbMotion.Idle))
                accepted.turn.outcome
            }
            is Destination.Work -> followWork(accepted, words)
        }
    }

    private suspend fun followWork(work: Destination.Work, words: String): String = coroutineScope {
        progress(VoiceCallProgress(OrbMotion.Connecting))
        val task = launch {
            app.taskState.runs.collectLatest { runs ->
                val run = runs.firstOrNull { it.conversationId == work.conversation && it.goal == words }
                    ?: return@collectLatest
                run.state.collect { state ->
                    progress(VoiceCallProgress(state.orbMotion, (state as? TaskState.Running)?.mcpConnector))
                }
            }
        }
        try {
            app.chat.ui.mapNotNull { ui ->
                if (ui.conversationId != work.conversation) return@mapNotNull null
                ui.exchanges.lastOrNull { it.startedAt == work.startedAt && it.goal == words }
            }.first { exchange ->
                when (exchange.status) {
                    is ExchangeStatus.Queued -> {
                        progress(VoiceCallProgress(OrbMotion.Waiting))
                        false
                    }
                    is ExchangeStatus.Paused -> exchange.status.resumable
                    ExchangeStatus.Working -> false
                    else -> true
                }
            }.let { exchange ->
                when (val status = exchange.status) {
                    is ExchangeStatus.Done -> {
                        progress(VoiceCallProgress(OrbMotion.Complete))
                        listOfNotNull(status.answer, status.unverifiedLine).joinToString("\n")
                    }
                    is ExchangeStatus.Failed -> {
                        progress(VoiceCallProgress(OrbMotion.Idle))
                        status.reason
                    }
                    is ExchangeStatus.Cancelled -> {
                        progress(VoiceCallProgress(OrbMotion.Idle))
                        status.summary ?: app.getString(R.string.trace_cancelled)
                    }
                    else -> {
                        progress(VoiceCallProgress(OrbMotion.Waiting, needsTaskView = true))
                        app.getString(R.string.mika_voice_call_task_waiting)
                    }
                }
            }
        } finally {
            task.cancel()
        }
    }
}
