package com.wangzan101.mika.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AssistantAppearance(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("assistant_appearance", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(app.noBackupFilesDir, "assistant_avatar.png"))

    var name: String
        get() = prefs.getString("name", "").orEmpty()
        set(value) { prefs.edit().putString("name", value.trim()).apply() }
    var animate: Boolean
        get() = prefs.getBoolean("animate", true)
        set(value) { prefs.edit().putBoolean("animate", value).apply() }

    suspend fun avatar(): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { file.openRead().use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    /** Keep a small, local copy without the photo's metadata. */
    suspend fun importAvatar(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, 384f / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return@withContext false
        try {
            val output = file.startWrite()
            try {
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    file.finishWrite(output)
                    true
                } else {
                    file.failWrite(output)
                    false
                }
            } catch (e: java.io.IOException) {
                file.failWrite(output)
                false
            }
        } catch (e: java.io.IOException) {
            false
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun clearAvatar() = withContext(Dispatchers.IO) { file.delete() }
}

@Composable
internal fun AssistantAvatar(
    bitmap: Bitmap?,
    diameter: Dp,
    modifier: Modifier = Modifier,
    eyes: AvatarEyes? = null,
    motion: AvatarExpression? = null,
) {
    val c = MikaTheme.colors
    Box(modifier.size(diameter).clip(CircleShape).background(c.accentSoft)) {
        if (bitmap != null && eyes != null && motion != null) {
            val phase = rememberAvatarPhase(motion)
            val mesh = remember(bitmap, eyes) { AvatarFaceMesh(bitmap, eyes) }
            val label = stringResource(R.string.assistant_avatar)
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = label }) {
                mesh.draw(this, motion, phase())
            }
        } else if (bitmap != null) {
            Image(
                remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = stringResource(R.string.assistant_avatar),
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            val phase = if (motion != null) rememberAvatarPhase(motion) else null
            Canvas(Modifier.matchParentSize()) {
                val d = size.minDimension
                val progress = phase?.invoke() ?: 0f
                val blink = if (motion != null) blinkAmount(progress, motion) else 0f
                val gaze = if (motion == AvatarExpression.Thinking) kotlin.math.sin(progress * Math.PI.toFloat()) * 0.045f else 0f
                val eyeHeight = d * (0.19f - blink * 0.165f)
                for (x in listOf(0.34f, 0.56f)) {
                    drawRoundRect(
                        c.accent, Offset(d * (x + gaze), d * (0.435f - gaze) - eyeHeight / 2),
                        Size(d * 0.10f, eyeHeight), CornerRadius(d * 0.05f),
                    )
                }
                drawArc(c.accent, 10f, 160f, false, Offset(d * 0.39f, d * 0.51f),
                    Size(d * 0.22f, d * 0.15f), style = androidx.compose.ui.graphics.drawscope.Stroke(d * 0.035f))
            }
        }
    }
}
