package com.system.location.service.core.carrier

/** Preserve MNC width; only ASCII decimal digits are valid PLMN wire characters. */
data class CarrierPlmnInput(val mcc: Int, val mnc: Int, val mncDigits: Int) {
    companion object {
        fun parse(mcc: String, mnc: String): CarrierPlmnInput {
            require(mcc.length == 3 && mcc.all { it in '0'..'9' }) { "MCC 必须为 3 位数字" }
            require(mnc.length in 2..3 && mnc.all { it in '0'..'9' }) { "MNC 必须为 2 或 3 位数字，保留前导零" }
            require(mcc.toInt() != 0) { "MCC 不能为 000" }
            return CarrierPlmnInput(mcc.toInt(), mnc.toInt(), mnc.length)
        }
    }
}
