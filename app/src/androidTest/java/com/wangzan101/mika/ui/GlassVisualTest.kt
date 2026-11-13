package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class GlassVisualTest {
    @get:Rule val compose = createComposeRule()
    @Test fun captureGlass() {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PageBackground(ambient = AmbientMode.Executing, reduceMotion = true) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("Mika")
                        MikaCard { Text("今天有两项待办，需要我帮你安排吗？", Modifier.padding(20.dp)) }
                        GlassBar { Text("按住说话，点一下打字", Modifier.padding(12.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val path = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "glass-check.png")
        path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
