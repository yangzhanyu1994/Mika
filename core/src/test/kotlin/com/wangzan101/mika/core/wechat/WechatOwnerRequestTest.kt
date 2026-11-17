package com.wangzan101.mika.core.wechat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatOwnerRequestTest {
    @Test fun automaticDeliveryOnlyForOptedInRuns() {
        val ui = com.wangzan101.mika.core.run.RunOrigin.Ui
        val trigger = com.wangzan101.mika.core.run.RunOrigin.Trigger
        val channel = com.wangzan101.mika.core.run.RunOrigin.Channel
        assertTrue(shouldSendOwnerResult(ui, "通过微信发给我", false, false))
        assertFalse(shouldSendOwnerResult(ui, "通过微信发给小王", false, false))
        assertTrue(shouldSendOwnerResult(trigger, "查看设备", false, true))
        assertFalse(shouldSendOwnerResult(trigger, "查看设备", false, false))
        assertFalse(shouldSendOwnerResult(trigger, "查看设备", true, true))
        assertFalse(shouldSendOwnerResult(channel, "通过微信发给我", false, true))
    }

    @Test fun onlyExplicitOwnerDirectedSendsAreAllowed() {
        assertTrue(explicitWechatOwnerRequest("奥智联锁车并汇报车辆状态，并且把消息推送到我的微信"))
        assertTrue(explicitWechatOwnerRequest("结果通过微信发给我"))
        assertTrue(explicitWechatOwnerRequest("查看家中设备状态。并把结果通过微信接口发送至微信。"))
        assertTrue(explicitWechatOwnerRequest("执行后发到微信"))
        assertTrue(explicitWechatOwnerRequest("微信通知我"))
        assertFalse(explicitWechatOwnerRequest("发送到微信给小王"))
        assertFalse(explicitWechatOwnerRequest("把结果发到微信小王"))
        assertTrue(explicitWechatOwnerRequest("推送到我的微信。不要把已提交当作成功"))
        assertFalse(explicitWechatOwnerRequest("给朋友微信发消息"))
        assertFalse(explicitWechatOwnerRequest("查看我的微信，然后发短信给我"))
        assertFalse(explicitWechatOwnerRequest("用我的微信发给小王"))
        assertFalse(explicitWechatOwnerRequest("不要推送到我的微信"))
        assertFalse(explicitWechatOwnerRequest("我的微信连接了吗"))
    }
}
