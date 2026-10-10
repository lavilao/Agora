package com.newoether.agora.ui.chat.bottombar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.api.CactusEngine
import com.newoether.agora.api.CactusSpeechEngine
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The composer's Whistle dictation button: hold to record, release to insert
 * the transcript, slide left to cancel - the WhatsApp interaction, running
 * Cactus' on-device speech model beside whichever chat model (Needle .cact
 * or a GGUF through llama.cpp) the conversation uses.
 *
 * The state object owns the whole lifecycle: the AudioRecord read loop on an
 * IO scope, the 30 s ceiling, the on-demand Whistle load and the final
 * transcription. The button is only its gesture surface, so gesture disposal
 * (window blur, rotation) finalizes cleanly through the same path.
 */
internal class ComposerVoiceRecorderState(private val appContext: Context) {

    internal enum class Phase { IDLE, RECORDING, TRANSCRIBING }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var cancelArmed by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var noticeRes by mutableStateOf<Int?>(null)
        private set
    var noticeArg by mutableStateOf<String?>(null)
        private set
    var availability by mutableStateOf(false)
        private set

    /** True when the needle engine + an installed Whistle model are present. */
    val isAvailable: Boolean get() = availability

    private var recordStartAtMs = 0L
    private var recorder: VoiceMicRecorder? = null
    private var recordingScope: CoroutineScope? = null
    private var deliver: ((String, Boolean) -> Unit)? = null
    private var autoSendIfEmpty = false
    private val finalizing = AtomicBoolean(false)

    /** Re-checks the engine and installed model; called on composition and resume. */
    fun refreshAvailability() {
        availability = CactusSpeechEngine.isModelAvailable(appContext)
    }

    /**
     * Starts a recording. [wasEmpty] records whether the composer was empty
     * when the hold began - its transcript then auto-sends, the WhatsApp
     * behaviour - and [deliverTranscript] receives the final text on the main
     * thread.
     */
    fun beginRecording(
        wasEmpty: Boolean,
        deliverTranscript: (String, Boolean) -> Unit,
    ): Boolean {
        if (phase != Phase.IDLE || !availability) return false
        val mic = VoiceMicRecorder()
        if (!mic.start()) return false
        finalizing.set(false)
        autoSendIfEmpty = wasEmpty
        deliver = deliverTranscript
        recorder = mic
        cancelArmed = false
        elapsedMs = 0L
        recordStartAtMs = SystemClock.elapsedRealtime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        recordingScope = scope
        phase = Phase.RECORDING
        scope.launch {
            // Drain the microphone continuously: an unread AudioRecord drops
            // samples once its buffer fills, which would punch holes in the clip.
            while (isActive && mic.totalSamples() < VOICE_MAX_SAMPLES) {
                if (mic.readChunk() < 0) break
            }
            // The engine takes at most 30 s: end the hold for the speaker.
            if (mic.totalSamples() >= VOICE_MAX_SAMPLES) endRecording(false)
        }
        return true
    }

    /** Marks (or unmarks) the slide-left cancel state while the hold lasts. */
    fun armCancel(armed: Boolean) {
        if (phase == Phase.RECORDING) cancelArmed = armed
    }

    /**
     * Ends the active recording: [cancelled] discards the clip, otherwise the
     * clip is transcribed and delivered. Safe to call repeatedly; an
     * already-finalized recording (the 30 s ceiling reached first) is a no-op.
     */
    fun endRecording(cancelled: Boolean) {
        val scope = recordingScope ?: return
        if (!finalizing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val mic = recorder ?: return@launch
                recorder = null
                mic.stopQuietly()
                if (cancelled) {
                    showNotice(R.string.voice_cancelled)
                    return@launch
                }
                val pcm = mic.takePcm()
                if (pcm.size < VOICE_MIN_SAMPLES) {
                    showNotice(R.string.voice_hold_to_talk)
                    return@launch
                }
                phase = Phase.TRANSCRIBING
                val model = CactusSpeechEngine.ensureModelLoaded(appContext)
                if (model == null) {
                    val reason = CactusEngine.lastError()
                    showNotice(R.string.voice_transcription_failed, reason.ifBlank { "Whistle" })
                    return@launch
                }
                val result = CactusSpeechEngine.transcribe(pcm)
                val error = result.error
                when {
                    error != null -> showNotice(R.string.voice_transcription_failed, error)
                    result.text.isBlank() -> showNotice(R.string.voice_empty_result)
                    else -> withContext(Dispatchers.Main) {
                        deliver?.invoke(result.text.trim(), autoSendIfEmpty)
                    }
                }
            } finally {
                phase = Phase.IDLE
                cancelArmed = false
                recordingScope = null
                deliver = null
                scope.cancel()
            }
        }
    }

    /** Displays one localized notice in the composer for a few seconds. */
    fun showNotice(resId: Int, argument: String? = null) {
        noticeRes = resId
        noticeArg = argument
    }

    internal fun clearNotice() {
        noticeRes = null
        noticeArg = null
    }

    internal fun tickElapsed() {
        if (phase == Phase.RECORDING) {
            elapsedMs = SystemClock.elapsedRealtime() - recordStartAtMs
        }
    }

    companion object {
        /** 16 kHz * 30 s: the engine's per-call ceiling. */
        internal const val VOICE_MAX_SAMPLES = 16000 * 30

        /** Below 250 ms the clip is a stray tap, not speech. */
        internal const val VOICE_MIN_SAMPLES = 16000 / 4

        /** Horizontal drag that arms the slide-to-cancel. */
        internal const val VOICE_CANCEL_DRAG_DP = 72
    }
}

/**
 * Remembers the dictation state, refreshing availability whenever the chat
 * surfaces (returning from the settings page where Whistle is downloaded)
 * and clearing stale notices on their own.
 */
@Composable
internal fun rememberComposerVoiceRecorderState(): ComposerVoiceRecorderState {
    val context = LocalContext.current
    val state = remember { ComposerVoiceRecorderState(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refreshAvailability()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        state.refreshAvailability()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.noticeRes) {
        if (state.noticeRes != null) {
            delay(3200)
            state.clearNotice()
        }
    }
    return state
}

/**
 * The microphone button itself: press and hold to record inside a custom
 * gesture, slide past [ComposerVoiceRecorderState.VOICE_CANCEL_DRAG_DP] to
 * arm the cancel, release to finalize. A press without permission only asks
 * for it; a disabled button holds its place without acting.
 */
@Composable
internal fun ComposerVoiceRecorderButton(
    state: ComposerVoiceRecorderState,
    enabled: Boolean,
    onCaptureEmpty: () -> Boolean,
    onTranscript: (text: String, autoSend: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalAgoraHaptics.current
    // The gesture coroutine outlives recompositions; the callbacks and enabled
    // flag must be read through their latest values, never through the
    // composition that happened to start the hold.
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnCaptureEmpty by rememberUpdatedState(onCaptureEmpty)
    val currentOnTranscript by rememberUpdatedState(onTranscript)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) state.showNotice(R.string.voice_permission_needed)
    }
    val cancelDragPx = with(LocalDensity.current) {
        ComposerVoiceRecorderState.VOICE_CANCEL_DRAG_DP.dp.toPx()
    }
    val busy = state.phase != ComposerVoiceRecorderState.Phase.IDLE
    Surface(
        shape = CircleShape,
        color = when {
            state.cancelArmed -> MaterialTheme.colorScheme.errorContainer
            busy -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            state.cancelArmed -> MaterialTheme.colorScheme.onErrorContainer
            busy -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .size(46.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    val began = state.beginRecording(
                        wasEmpty = currentOnCaptureEmpty(),
                        deliverTranscript = currentOnTranscript,
                    )
                    if (!began) return@awaitEachGesture
                    val startX = down.position.x
                    var armed = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val press = event.changes.firstOrNull { it.id == down.id }
                            if (press == null || !press.pressed) break
                            val nowArmed = (press.position.x - startX) < -cancelDragPx
                            if (nowArmed != armed) {
                                armed = nowArmed
                                state.armCancel(armed)
                                haptics.selection()
                            }
                            press.consume()
                        }
                    } finally {
                        state.endRecording(armed)
                    }
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (state.phase) {
                ComposerVoiceRecorderState.Phase.TRANSCRIBING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                else -> Icon(
                    Icons.Default.Mic,
                    contentDescription = stringResource(R.string.voice_input_desc),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * What the composer's control group is replaced with while dictation is
 * active: the red recording dot, the timer and slide-to-cancel hint, the
 * transcribing notice, or the last transient notice once it settles.
 */
@Composable
internal fun ComposerVoiceRecordingHud(
    state: ComposerVoiceRecorderState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.phase) {
        while (state.phase == ComposerVoiceRecorderState.Phase.RECORDING) {
            state.tickElapsed()
            delay(100)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.phase) {
            ComposerVoiceRecorderState.Phase.RECORDING -> {
                RecordingDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    formatVoiceElapsed(state.elapsedMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        if (state.cancelArmed) {
                            R.string.voice_release_to_cancel
                        } else {
                            R.string.voice_slide_to_cancel
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.cancelArmed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ComposerVoiceRecorderState.Phase.TRANSCRIBING -> {
                Text(
                    stringResource(R.string.voice_transcribing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ComposerVoiceRecorderState.Phase.IDLE -> {
                val res = state.noticeRes
                if (res != null) {
                    Text(
                        stringResource(res, state.noticeArg.orEmpty()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingDot() {
    val transition = rememberInfiniteTransition(label = "voiceRecordingDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voiceRecordingDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(Color(0xFFE53935), CircleShape),
    )
}

private fun formatVoiceElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceIn(0, 5999)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Blocking 16 kHz mono PCM16 capture. Every method is called from the
 * recording scope's IO coroutines only; [takePcm] assembles the whole clip
 * once, as floats in [-1, 1] the engine consumes.
 */
private class VoiceMicRecorder {
    private var record: AudioRecord? = null
    private val chunks = ArrayList<ShortArray>()
    private val total = AtomicInteger(0)

    fun start(): Boolean {
        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                VOICE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (_: Exception) {
            -1
        }
        if (minBuffer <= 0) return false
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                VOICE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, 8192),
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return false
        }
        try {
            audio.startRecording()
        } catch (_: IllegalStateException) {
            audio.release()
            return false
        }
        record = audio
        return true
    }

    /** Blocking read of one chunk; returns the samples read, or -1 when done. */
    fun readChunk(): Int {
        val audio = record ?: return -1
        val chunk = ShortArray(2048)
        val read = try {
            audio.read(chunk, 0, chunk.size)
        } catch (_: Exception) {
            -1
        }
        if (read <= 0) return -1
        synchronized(chunks) {
            chunks.add(chunk.copyOf(read))
            total.addAndGet(read)
        }
        return read
    }

    fun totalSamples(): Int = total.get()

    /** Stops capture; the AudioRecord itself is released and never reused. */
    fun stopQuietly() {
        val audio = record ?: return
        record = null
        try {
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audio.stop()
            }
        } catch (_: IllegalStateException) {
            // Already stopped or released; nothing to drain.
        }
        audio.release()
    }

    /** Assembles the recorded clip as float PCM in [-1, 1]. */
    fun takePcm(): FloatArray {
        val captured: List<ShortArray>
        synchronized(chunks) {
            captured = ArrayList(chunks)
            chunks.clear()
        }
        var count = 0
        for (chunk in captured) count += chunk.size
        val pcm = FloatArray(count)
        var offset = 0
        for (chunk in captured) {
            for (i in chunk.indices) {
                pcm[offset + i] = chunk[i] / 32768.0f
            }
            offset += chunk.size
        }
        return pcm
    }

    private companion object {
        const val VOICE_SAMPLE_RATE = 16000
    }
}
