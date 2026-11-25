package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.AppLaunchFiles
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons

/** 已记住的任务到应用。查看、删除、清空都在设置里，不另做一套管理。 */
@Composable
fun AppLaunchPrefsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember { AppLaunchFiles(context.filesDir) }
    var tick by remember { mutableIntStateOf(0) }
    val items = remember(tick) { files.current() }
    PageScaffold(stringResource(R.string.app_launch_title), onBack) {
        FooterNote(stringResource(R.string.app_launch_note))
        if (items.isEmpty()) {
            FooterNote(stringResource(R.string.app_launch_empty))
        } else {
            GroupCard {
                items.forEachIndexed { index, item ->
                    val kind = stringResource(if (item.confirmed) R.string.app_launch_confirmed else R.string.app_launch_used)
                    val phrase = item.phrase.ifBlank { stringResource(R.string.app_launch_used) }
                    val copy = item.app.userId?.let { " · $it" }.orEmpty()
                    ListRow(
                        MikaIcons.Phone,
                        item.app.label,
                        "$kind · $phrase · ${item.app.packageName}$copy",
                        first = index == 0,
                        onClick = {
                            files.remove(item)
                            tick++
                        },
                    ) { RowValue(stringResource(R.string.app_launch_remove)) }
                }
            }
            GroupCard {
                ListRow(MikaIcons.Trash, stringResource(R.string.app_launch_clear), first = true, onClick = {
                    files.clear()
                    tick++
                })
            }
        }
    }
}
