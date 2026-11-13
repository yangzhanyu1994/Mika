package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSettingsGreetingTest {
    @Test fun customAndExplicitlyEmptyGreetingsSurviveReopeningSettings() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        assertEquals(CallPolicy.DEFAULT_GREETING, settings.state.value.greeting)
        assertEquals(CallPolicy.DEFAULT_OUTGOING_GREETING, settings.state.value.outgoingGreeting)

        settings.save(settings.state.value.copy(greeting = "你好", systemPrompt = "你好"))
        assertEquals("你好", prefs.getString("greeting", null))
        val reopened = CallSettings(prefs)
        assertEquals("你好", reopened.state.value.greeting)
        assertEquals("你好", reopened.state.value.systemPrompt)

        reopened.save(reopened.state.value.copy(greeting = ""))
        assertEquals("", prefs.getString("greeting", null))
        val cleared = CallSettings(prefs).state.value
        assertEquals("", cleared.greeting)
        assertEquals("你好", cleared.systemPrompt)
    }

    @Test fun incomingOutgoingAndDefaultSimAreIndependentAndSurviveUpgrade() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("greeting", "已有的接听开场白").commit()
        val settings = CallSettings(prefs)
        assertEquals("已有的接听开场白", settings.state.value.greeting)
        assertEquals(CallPolicy.DEFAULT_OUTGOING_GREETING, settings.state.value.outgoingGreeting)
        settings.save(settings.state.value.copy(outgoingGreeting = "这是拨出电话", defaultOutgoingSimId = "sim-2"))
        val reopened = CallSettings(prefs).state.value
        assertEquals("已有的接听开场白", reopened.greeting)
        assertEquals("这是拨出电话", reopened.outgoingGreeting)
        assertEquals("sim-2", reopened.defaultOutgoingSimId)
        assertEquals("已有的接听开场白", CallPolicy.callGreeting(reopened, CallDirection.INCOMING))
        assertEquals("这是拨出电话", CallPolicy.callGreeting(reopened, CallDirection.OUTGOING))
        settings.save(settings.state.value.copy(outgoingGreeting = "", defaultOutgoingSimId = null))
        assertEquals("", CallSettings(prefs).state.value.outgoingGreeting)
        assertEquals(null, CallSettings(prefs).state.value.defaultOutgoingSimId)
    }
}
