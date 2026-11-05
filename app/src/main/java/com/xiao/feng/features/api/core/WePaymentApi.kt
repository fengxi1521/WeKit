package com.xiao.feng.features.api.core

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexConstructor
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.net.WeNetSceneApi
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.WeLogger

object WePaymentApi : ApiFeature(), IResolveDex {

    override val technicalId = "支付服务"
    override val nameRes = R.string.feature_we_payment_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_payment_api_description

    private const val TAG = "WePaymentApi"

    private val ctorNetSceneTransferOperation by dexConstructor()
    val classReceiveLuckyMoney by dexClass()
    val classOpenLuckyMoney by dexClass()
    val methodReceiveLuckyMoneyOnGYNetEnd by dexMethod()
    val methodOpenLuckyMoneyOnGYNetEnd by dexMethod()

    fun confirmTransfer(transactionId: String, transferId: String, payerUsername: String, invalidTime: Int): Boolean {
        return executeTransferOperation("confirm", transactionId, transferId, payerUsername, invalidTime)
    }

    fun refuseTransfer(transactionId: String, transferId: String, payerUsername: String, invalidTime: Int): Boolean {
        return executeTransferOperation("refuse", transactionId, transferId, payerUsername, invalidTime)
    }

    private fun executeTransferOperation(
        operation: String,
        transactionId: String,
        transferId: String,
        payerUsername: String,
        invalidTime: Int
    ): Boolean {
        return try {
            val ctor = ctorNetSceneTransferOperation.constructor
            val netScene = when (ctor.parameterCount) {
                10 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null)
                12 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null, 0L, "")
                13 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null, 0L, "", "")
                14 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, "", null, 0L, "", "")
                else -> error("unknown NetSceneTransferOperation constructor variant")
            }
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "${operation}Transfer failed", e)
            false
        }
    }
}
