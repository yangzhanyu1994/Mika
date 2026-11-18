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
internal fun AssistantAvatar(bitmap: Bitmap?, diameter: Dp, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Box(modifier.size(diameter).clip(CircleShape).background(c.accentSoft)) {
        if (bitmap != null) {
            Image(
                remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = stringResource(R.string.assistant_avatar),
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Canvas(Modifier.matchParentSize()) {
                val d = size.minDimension
                for (x in listOf(0.34f, 0.56f)) {
                    drawRoundRect(c.accent, Offset(d * x, d * 0.34f), Size(d * 0.10f, d * 0.19f), CornerRadius(d * 0.05f))
                }
                drawArc(c.accent, 10f, 160f, false, Offset(d * 0.39f, d * 0.51f),
                    Size(d * 0.22f, d * 0.15f), style = androidx.compose.ui.graphics.drawscope.Stroke(d * 0.035f))
            }
        }
    }
}
