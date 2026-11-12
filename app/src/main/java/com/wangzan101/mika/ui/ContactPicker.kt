package com.wangzan101.mika.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.PhoneContacts
import com.wangzan101.mika.calls.PhoneNumbers
import com.wangzan101.mika.ui.theme.MikaTheme

@Composable
internal fun rememberPhoneContacts(): PhoneContacts {
    val context = LocalContext.current
    return remember(context) { PhoneContacts(context) }
}

/** Multi-select from the address book. Denied permission leaves the caller on manual numbers. */
@Composable
internal fun ContactMultiPicker(
    already: Set<String>,
    onDismiss: () -> Unit,
    onApply: (List<PhoneNumbers.Entry>) -> Unit,
) {
    val context = LocalContext.current
    val book = remember(context) { PhoneContacts(context) }
    var granted by remember { mutableStateOf(book.granted()) }
    var explain by remember { mutableStateOf(!granted) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PhoneContacts.clearCache()
        granted = book.granted()
        explain = false
    }
    val rows = remember(granted, query) {
        if (!granted) emptyList() else book.entries().filter { entry ->
            query.isBlank() || entry.name.contains(query, ignoreCase = true) || entry.number.contains(query)
        }.take(200)
    }
    if (explain && !granted) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.calls_contacts_row)) },
            text = { Text(stringResource(R.string.calls_contacts_why)) },
            confirmButton = {
                TextButton(onClick = { request.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text(stringResource(R.string.calls_contacts_allow))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calls_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calls_contacts_pick)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) {
                    Text(stringResource(R.string.calls_contacts_missing), color = MaterialTheme.colorScheme.error)
                } else {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("contact-search"),
                        label = { Text(stringResource(R.string.calls_contacts_search)) }, singleLine = true)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (rows.isEmpty()) Text(stringResource(R.string.calls_contacts_empty), color = MikaTheme.colors.ink3)
                        rows.forEach { entry ->
                            val selected = entry.number in picked || already.any { PhoneNumbers.same(it, entry.number) }
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    picked = if (entry.number in picked) picked - entry.number else picked + entry.number
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(selected, onCheckedChange = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(entry.number, style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = {
                    val chosen = book.entries().filter { it.number in picked }
                    onApply(chosen)
                },
            ) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
internal fun ContactSinglePicker(onDismiss: () -> Unit, onPick: (PhoneNumbers.Entry) -> Unit) {
    ContactMultiPicker(emptySet(), onDismiss) { chosen -> chosen.firstOrNull()?.let(onPick) }
}

@Composable
internal fun ContactNameLine(number: String): String {
    val book = rememberPhoneContacts()
    var label by remember(number) { mutableStateOf(number) }
    LaunchedEffect(number) { label = book.label(number) }
    return label
}
