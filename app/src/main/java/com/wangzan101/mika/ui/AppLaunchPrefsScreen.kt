package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.AppLaunch
import com.wangzan101.mika.core.agent.AppLaunchFiles
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已记住的任务到应用。查看、删除、清空都在设置里，不另做一套管理。 */
@Composable
fun AppLaunchPrefsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember { AppLaunchFiles(context.filesDir) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AppLaunch.Preference>?>(null) }
    LaunchedEffect(files) {
        items = withContext(Dispatchers.IO) { files.current() }
    }
    PageScaffold(stringResource(R.string.app_launch_title), onBack) {
        FooterNote(stringResource(R.string.app_launch_note))
        val loaded = items
        if (loaded == null) return@PageScaffold
        if (loaded.isEmpty()) {
            FooterNote(stringResource(R.string.app_launch_empty))
        } else {
            GroupCard {
                loaded.forEachIndexed { index, item ->
                    val kind = stringResource(if (item.confirmed) R.string.app_launch_confirmed else R.string.app_launch_used)
                    ListRow(
                        MikaIcons.Phone,
                        item.app.label,
                        appLaunchPrefSubtitle(kind, item),
                        first = index == 0,
                        onClick = {
                            scope.launch {
                                items = withContext(Dispatchers.IO) {
                                    files.remove(item)
                                    files.current()
                                }
                            }
                        },
                    ) { RowValue(stringResource(R.string.app_launch_remove)) }
                }
            }
            GroupCard {
                ListRow(MikaIcons.Trash, stringResource(R.string.app_launch_clear), first = true, onClick = {
                    scope.launch {
                        items = withContext(Dispatchers.IO) {
                            files.clear()
                            files.current()
                        }
                    }
                })
            }
        }
    }
}

internal fun appLaunchPrefSubtitle(kind: String, item: AppLaunch.Preference): String =
    listOfNotNull(
        kind,
        item.phrase.takeIf(String::isNotBlank),
        item.app.packageName,
        item.app.userId?.toString(),
    ).joinToString(" · ")
