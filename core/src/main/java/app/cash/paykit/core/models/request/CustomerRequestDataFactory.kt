/*
 * Copyright (C) 2023 Cash App
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.paykit.core.models.request

import app.cash.paykit.core.models.common.Action
import app.cash.paykit.core.models.pii.PiiString
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction.OnFileAction
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction.OneTimeAction

/**
 * Factory that will create a [CreateCustomerRequest] from a [CashAppPayPaymentAction].
 */
object CustomerRequestDataFactory {

  internal const val CHANNEL_IN_APP = "IN_APP"
  private const val PAYMENT_TYPE_ONE_TIME = "ONE_TIME_PAYMENT"
  private const val PAYMENT_TYPE_ON_FILE = "ON_FILE_PAYMENT"

  fun build(
    clientId: String,
    redirectUri: String?,
    paymentActions: List<CashAppPayPaymentAction>,
    isRequestUpdate: Boolean = false,
  ): CustomerRequestData {
    val actions = ArrayList<Action>(paymentActions.size)
    for (paymentAction in paymentActions) {
      when (paymentAction) {
        is OnFileAction -> actions.add(buildFromOnFileAction(clientId, paymentAction))
        is OneTimeAction -> actions.add(buildFromOneTimeAction(clientId, paymentAction))
      }
    }

    return if (isRequestUpdate) {
      CustomerRequestData(
        actions = actions,
        channel = null, // TODO why is this null??
        redirectUri = null,
      )
    } else {
      CustomerRequestData(
        actions = actions,
        channel = CHANNEL_IN_APP,
        redirectUri = redirectUri?.let { PiiString(redirectUri) },
      )
    }
  }

  private fun buildFromOnFileAction(
    clientId: String,
    onFileAction: OnFileAction,
  ): Action {
    // Create request data.
    val scopeIdOrClientId = onFileAction.scopeId ?: clientId

    return Action(
      scopeId = scopeIdOrClientId,
      type = PAYMENT_TYPE_ON_FILE,
    )
  }

  private fun buildFromOneTimeAction(
    clientId: String,
    oneTimeAction: OneTimeAction,
  ): Action {
    // Create request data.
    val scopeIdOrClientId = oneTimeAction.scopeId ?: clientId
    return Action(
      amount_cents = oneTimeAction.amount,
      currency = oneTimeAction.currency?.backendValue,
      scopeId = scopeIdOrClientId,
      type = PAYMENT_TYPE_ONE_TIME,
    )
  }
}
