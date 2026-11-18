package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.assistant.AssistantAppearance
import com.wangzan101.mika.assistant.AssistantAvatar
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import kotlinx.coroutines.launch

@Composable
internal fun AssistantAppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appearance = remember { AssistantAppearance(context) }
    val scope = rememberCoroutineScope()
    var avatar by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf(appearance.name) }
    var animate by remember { mutableStateOf(appearance.animate) }
    LaunchedEffect(appearance) {
        avatar = appearance.avatar()
        busy = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    if (appearance.importAvatar(uri)) avatar = appearance.avatar()
                    else Toast.makeText(context, R.string.skin_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    busy = false
                }
            }
        }
    }
    PageScaffold(stringResource(R.string.assistant_appearance), onBack) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AssistantAvatar(avatar, 96.dp)
            TextButton(enabled = !busy, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.assistant_change_avatar)) }
            if (avatar != null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            appearance.clearAvatar()
                            avatar = null
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.assistant_default_avatar)) }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { value ->
                if (value.length <= 32 && value.none(Char::isISOControl)) {
                    name = value
                    appearance.name = value
                }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            label = { Text(stringResource(R.string.assistant_display_name)) },
            placeholder = { Text(stringResource(R.string.app_name)) },
            singleLine = true,
        )
        GroupCard {
            ListRow(null, stringResource(R.string.assistant_wait_animation), first = true) {
                MikaSwitch(checked = animate, onCheckedChange = { animate = it; appearance.animate = it })
            }
        }
    }
}
