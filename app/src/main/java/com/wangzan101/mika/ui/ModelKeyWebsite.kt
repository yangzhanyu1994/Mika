package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.FooterNote
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val DOUBAO_VOICE_KEY_GUIDE = "https://docs.volcengine.com/docs/doubao-voice/console-guidance?lang=zh"

internal fun modelKeyWebsite(baseUrl: String): String? = when (baseUrl.trim().toHttpUrlOrNull()?.host) {
    "api.deepseek.com" -> "https://platform.deepseek.com/api_keys"
    "api.openai.com" -> "https://platform.openai.com/api-keys"
    "ark.cn-beijing.volces.com" -> "https://ark.volcengine.com/region:cn-beijing/openManagement?advancedActiveKey=model"
    else -> null
}

@Composable
internal fun ModelKeyWebsite(url: String?) {
    val context = LocalContext.current
    if (url == null) {
        FooterNote(stringResource(R.string.model_key_website_hint))
    } else {
        TextButton(onClick = { openInBrowser(context, url) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.drawer_model_website))
        }
    }
}
