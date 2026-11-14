package com.wangzan101.mika.calls

/** Reasons contain no phone numbers, call IDs or other personal data. */
internal enum class CarrierCallIssue(val retryable: Boolean, val message: String) {
    DETAILS_PENDING(true, "系统通话信息暂未就绪，AI 未接管"),
    ACCOUNT_PENDING(true, "系统尚未确认通话的运营商账号，AI 未接管"),
    SUBSCRIPTION_PENDING(true, "系统尚未确认通话的 SIM 信息，AI 未接管"),
    UNSUPPORTED_CALL(false, "当前通话类型不支持 AI 安全接管"),
    INVALID_NUMBER(false, "无法确认通话号码，AI 未接管"),
    EMERGENCY_OR_UNKNOWN(false, "无法确认是否为紧急电话，AI 未接管"),
}

internal enum class CarrierGuardDecision { READY, WAIT, REJECT_CHANGED_CALL, REJECT_CARRIER }

/** A missing system field may settle briefly; a changed call is never waited through. */
internal fun carrierGuardDecision(issue: CarrierCallIssue?, sameActiveSingleCall: Boolean, elapsedMs: Long): CarrierGuardDecision = when {
    !sameActiveSingleCall -> CarrierGuardDecision.REJECT_CHANGED_CALL
    issue == null -> CarrierGuardDecision.READY
    issue.retryable && elapsedMs < CARRIER_READY_WAIT_MS -> CarrierGuardDecision.WAIT
    else -> CarrierGuardDecision.REJECT_CARRIER
}

internal const val CARRIER_READY_WAIT_MS = 2_000L
