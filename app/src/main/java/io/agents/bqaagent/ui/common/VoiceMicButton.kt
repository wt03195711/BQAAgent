// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.common

import android.widget.Toast
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.agents.bqaagent.R
import io.agents.bqaagent.ui.chat.PokeclawColors
import io.agents.bqaagent.utils.PermissionUtils
import io.agents.bqaagent.utils.SherpaOnnxHelper

/**
 * Shared hold-to-talk microphone button, backed by the local sherpa-onnx ASR engine.
 *
 * Extracted from chat's input bar so chat and the batch add-task dialog share one interaction
 * contract: press to start recording, release to stop (no separate start/stop control). While
 * recording the button pulses with the accent color. Recognized text (partial + final) is pushed
 * to [onText]; recording state changes are mirrored through [onRecordingChange] so hosts can, for
 * example, highlight their text field border.
 *
 * Renders nothing when [voiceEnabled] is false. The ASR model is prepared lazily: mounting the
 * host merely kicks off model initialization; if the user presses before it is ready a toast
 * explains that the model is still loading (same passive-load policy as chat).
 *
 * [idleBackground] / [idleBorder] let floating variants (e.g. over a text field) stay legible on
 * top of content; chat keeps them transparent so the button blends into the input bar row.
 */
@Composable
fun VoiceMicButton(
    voiceEnabled: Boolean,
    colors: PokeclawColors,
    onRequestRecordPermission: () -> Unit,
    onText: (String) -> Unit,
    onRecordingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    idleBackground: Color = Color.Transparent,
    idleBorder: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    if (!voiceEnabled) return

    val context = LocalContext.current
    var asrHelper by remember { mutableStateOf<SherpaOnnxHelper?>(null) }
    var isVoiceRecording by remember { mutableStateOf(false) }
    var isClickProcessing by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    fun setRecording(recording: Boolean) {
        isVoiceRecording = recording
        onRecordingChange(recording)
    }

    LaunchedEffect(voiceEnabled) {
        if (asrHelper == null) {
            asrHelper = SherpaOnnxHelper(context)
        }
        asrHelper?.let { helper ->
            if (!helper.isModelReady) {
                helper.ensureModel()
            }
        }
    }

    // Force-release the ASR instance on dispose to keep the engine from hanging
    DisposableEffect(Unit) {
        onDispose {
            asrHelper?.release()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(buttonSize)
            .alpha(if (enabled) 1f else 0.35f)
            .then(
                if (isVoiceRecording) {
                    Modifier
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = pulseAlpha))
                        .then(Modifier.graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                        })
                } else {
                    Modifier
                        .clip(CircleShape)
                        .background(idleBackground)
                        .border(1.dp, idleBorder, CircleShape)
                }
            )
            .pointerInput(enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()

                    if (!enabled) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    if (isClickProcessing) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    isClickProcessing = true

                    val ctx = context

                    if (!PermissionUtils.hasRecordAudio(ctx)) {
                        onRequestRecordPermission()
                        isClickProcessing = false
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    val helper = asrHelper
                    if (helper == null || !helper.isModelReady) {
                        Toast.makeText(ctx, ctx.getString(R.string.chat_voice_model_loading), Toast.LENGTH_SHORT).show()
                        isClickProcessing = false
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    setRecording(true)
                    @Suppress("MissingPermission")
                    helper.startRecognition(object : SherpaOnnxHelper.AsrCallback {
                        override fun onPartialText(inputText: String) { onText(inputText) }
                        override fun onFinalText(inputText: String) {
                            onText(inputText)
                            setRecording(false)
                            isClickProcessing = false
                        }
                        override fun onError(errorMsg: String) {
                            Toast.makeText(ctx, ctx.getString(R.string.chat_voice_recognize_failed, errorMsg), Toast.LENGTH_SHORT).show()
                            setRecording(false)
                            isClickProcessing = false
                        }
                    })

                    val up = waitForUpOrCancellation()
                    up?.consume()

                    if (helper.isRecording()) {
                        helper.stopRecognition()
                    }
                    setRecording(false)
                    isClickProcessing = false
                }
            },
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(R.string.chat_voice_hold_to_talk),
            tint = if (isVoiceRecording) Color.White else colors.textSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}