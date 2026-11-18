package com.wangzan101.mika.assistant

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PointF
import android.media.FaceDetector
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import kotlin.math.sin

internal enum class AvatarExpression { Thinking, Working, Done }

internal data class AvatarEyes(val centerX: Float, val centerY: Float, val distance: Float) {
    companion object {
        /** Android's landmark detector runs locally and expects even-width RGB565 input. */
        fun detect(bitmap: Bitmap): AvatarEyes? {
            if (bitmap.width < 32 || bitmap.height < 32) return null
            val inputWidth = bitmap.width - bitmap.width % 2
            val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, bitmap.height, true)
            val input = scaled.copy(Bitmap.Config.RGB_565, false)
            if (scaled !== bitmap) scaled.recycle()
            if (input == null) return null
            return try {
                val faces = arrayOfNulls<FaceDetector.Face>(2)
                val count = FaceDetector(input.width, input.height, faces.size).findFaces(input, faces)
                val face = faces[0]
                if (count != 1 || face == null || face.confidence() < 0.5f ||
                    abs(face.pose(FaceDetector.Face.EULER_Y)) > 20f ||
                    abs(face.pose(FaceDetector.Face.EULER_Z)) > 15f
                ) return null
                val midpoint = PointF().also(face::getMidPoint)
                val scale = bitmap.width.toFloat() / input.width
                AvatarEyes(midpoint.x * scale, midpoint.y, face.eyesDistance() * scale)
                    .takeIf { it.distance >= 14f && it.centerY in 0f..bitmap.height.toFloat() }
            } catch (_: RuntimeException) {
                null
            } finally {
                input.recycle()
            }
        }
    }
}

@Composable
internal fun rememberAvatarPhase(expression: AvatarExpression): () -> Float {
    if (expression == AvatarExpression.Done) {
        val motion = remember { Animatable(0f) }
        LaunchedEffect(Unit) { motion.animateTo(1f, tween(720, easing = LinearEasing)) }
        return { motion.value }
    }
    val transition = rememberInfiniteTransition(label = "avatar expression")
    val progress = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (expression == AvatarExpression.Thinking) 2850 else 2300, easing = LinearEasing)),
        label = "avatar expression phase",
    )
    return { progress.value }
}

internal fun blinkAmount(phase: Float, expression: AvatarExpression): Float {
    val center = when (expression) {
        AvatarExpression.Thinking -> 0.68f
        AvatarExpression.Working -> 0.42f
        AvatarExpression.Done -> 0.46f
    }
    return (1f - abs(phase - center) / 0.075f).coerceIn(0f, 1f)
}

/** Deform only neighborhoods around the eyes. The round crop and photo background stay put. */
internal class AvatarFaceMesh(private val bitmap: Bitmap, private val eyes: AvatarEyes) {
    private val divisions = 40
    private val points = FloatArray((divisions + 1) * (divisions + 1) * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(scope: DrawScope, expression: AvatarExpression, phase: Float) = with(scope) {
        val blink = blinkAmount(phase, expression)
        val gaze = if (expression == AvatarExpression.Thinking) sin(phase * Math.PI.toFloat()) else 0f
        val eyeRadius = eyes.distance * 0.32f
        val eyeYRadius = eyes.distance * 0.24f
        val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
        val eyeX = eyes.distance * 0.5f
        var index = 0
        for (row in 0..divisions) {
            val y = row * bitmap.height.toFloat() / divisions
            val dy = y - eyes.centerY
            for (column in 0..divisions) {
                val x = column * bitmap.width.toFloat() / divisions
                val dx = abs(abs(x - eyes.centerX) - eyeX)
                val weight = (1f - (dx / eyeRadius) * (dx / eyeRadius) -
                    (dy / eyeYRadius) * (dy / eyeYRadius)).coerceAtLeast(0f)
                val blend = weight * weight
                points[index++] = x + gaze * blend * eyes.distance * 0.025f
                points[index++] = y - blink * blend * dy * 0.85f - gaze * blend * eyes.distance * 0.012f
            }
        }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val restore = native.save()
            native.translate((size.width - bitmap.width * scale) / 2f, (size.height - bitmap.height * scale) / 2f)
            native.scale(scale, scale)
            native.drawBitmapMesh(bitmap, divisions, divisions, points, 0, null, 0, paint)
            native.restoreToCount(restore)
        }
    }
}
