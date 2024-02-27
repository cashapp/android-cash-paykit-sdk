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
package app.cash.paykit.core.analytics

import app.cash.paykit.core.CashAppPayState
import app.cash.paykit.core.CashAppPayState.Approved
import app.cash.paykit.core.CashAppPayState.CashAppPayExceptionState
import app.cash.paykit.core.models.common.Action
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction
import app.cash.paykit.core.state.PayKitMachineStates

/**
 * Definition of analytics events that we want to capture.
 */
internal interface PayKitAnalyticsEventDispatcher {

  fun sdkInitialized()

  fun eventListenerAdded()

  fun eventListenerRemoved()

  fun createdCustomerRequest(
    paymentKitActions: List<CashAppPayPaymentAction>,
    apiActions: List<Action>,
    redirectUri: String?,
  )

  fun updatedCustomerRequest(
    requestId: String,
    paymentKitActions: List<CashAppPayPaymentAction>,
    apiActions: List<Action>,
  )

  fun genericStateChanged(cashAppPayState: PayKitMachineStates, customerResponseData: CustomerResponseData?)

  fun stateApproved(data: CustomerResponseData)

  fun exceptionOccurred(
    payKitExceptionState: CashAppPayExceptionState,
    customerResponseData: CustomerResponseData?,
  )

  /**
   * Command this [PayKitAnalyticsEventDispatcher] to stop executing and discard.
   */
  fun shutdown()
}
