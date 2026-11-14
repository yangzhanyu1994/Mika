package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CarrierCallGuardTest {
    @Test fun missingTransientDetailsCanSettleButCannotBypassTimeout() {
        for (issue in listOf(CarrierCallIssue.DETAILS_PENDING, CarrierCallIssue.ACCOUNT_PENDING, CarrierCallIssue.SUBSCRIPTION_PENDING)) {
            assertEquals(CarrierGuardDecision.WAIT, carrierGuardDecision(issue, true, 0))
            assertEquals(CarrierGuardDecision.WAIT, carrierGuardDecision(issue, true, CARRIER_READY_WAIT_MS - 1))
            assertEquals(CarrierGuardDecision.REJECT_CARRIER, carrierGuardDecision(issue, true, CARRIER_READY_WAIT_MS))
        }
        assertEquals(CarrierGuardDecision.READY, carrierGuardDecision(null, true, 500))
    }

    @Test fun unsafeOrChangedCallsNeverProceedOrWait() {
        for (issue in listOf(CarrierCallIssue.UNSUPPORTED_CALL, CarrierCallIssue.INVALID_NUMBER, CarrierCallIssue.EMERGENCY_OR_UNKNOWN)) {
            assertEquals(CarrierGuardDecision.REJECT_CARRIER, carrierGuardDecision(issue, true, 0))
        }
        assertEquals(CarrierGuardDecision.REJECT_CHANGED_CALL, carrierGuardDecision(null, false, 0))
        assertEquals(CarrierGuardDecision.REJECT_CHANGED_CALL, carrierGuardDecision(CarrierCallIssue.ACCOUNT_PENDING, false, 0))
    }
}
