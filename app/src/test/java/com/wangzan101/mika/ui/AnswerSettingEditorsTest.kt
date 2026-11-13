package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerSettingEditorsTest {
    @Test fun everyAnswerSettingsRowOpensOnlyItsOwnEditor() {
        val rows = AnswerSettingRow.entries
        val editors = rows.map(::answerSettingEditor)
        assertEquals(rows.size, editors.size)
        val shared = editors.filter { it != AnswerSettingEditor.None }.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue(shared.toString(), shared.isEmpty())
        val fields = AnswerSettingEditor.entries.filter { it != AnswerSettingEditor.None }.map { editor ->
            editor to answerSettingFields(editor)
        }
        fields.forEach { (editor, owned) -> assertTrue(editor.name, owned.isNotEmpty()) }
        for (i in fields.indices) for (j in i + 1 until fields.size) {
            val overlap = fields[i].second.intersect(fields[j].second)
            assertTrue("${fields[i].first} overlaps ${fields[j].first}: $overlap", overlap.isEmpty())
        }
        assertEquals(setOf("audioMode", "audioVerified"), answerSettingFields(AnswerSettingEditor.AudioChannel))
        assertEquals(setOf("greeting"), answerSettingFields(AnswerSettingEditor.Greeting))
        assertEquals(setOf("outgoingGreeting"), answerSettingFields(AnswerSettingEditor.OutgoingGreeting))
        assertEquals(setOf("defaultOutgoingSimId"), answerSettingFields(AnswerSettingEditor.DefaultSim))
        assertEquals(setOf("maxCallSeconds"), answerSettingFields(AnswerSettingEditor.Duration))
        assertTrue("speaker" in answerSettingFields(AnswerSettingEditor.Credentials))
        assertEquals(setOf("answerStrangersOnly"), answerSettingFields(AnswerSettingEditor.StrangerOnly))
        assertEquals(setOf("answerOnRingTimeout"), answerSettingFields(AnswerSettingEditor.RingTimeout))
    }
}
