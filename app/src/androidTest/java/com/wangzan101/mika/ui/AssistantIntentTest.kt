package com.wangzan101.mika.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantIntentTest {
    @Test fun appResolvesSystemAssistantIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_ASSIST).addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(handlers.any { it.activityInfo.name == MainActivity::class.java.name && it.activityInfo.exported })
    }
}
