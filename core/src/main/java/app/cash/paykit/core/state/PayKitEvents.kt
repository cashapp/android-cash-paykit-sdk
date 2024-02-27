/*
 * Copyright (C) 2023 Cash App
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package app.cash.paykit.core.state

import app.cash.paykit.core.exceptions.CashAppPayIntegrationException
import app.cash.paykit.core.models.request.CustomerRequestData
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction
import app.cash.paykit.core.state.ClientEventPayload.CreatingCustomerRequestData
import app.cash.paykit.core.state.ClientEventPayload.UpdateCustomerRequestData
import ru.nsk.kstatemachine.DataEvent
import ru.nsk.kstatemachine.Event

/**
 * Client provided data
 */
sealed interface ClientEventPayload {

  data class CreatingCustomerRequestData(
    val actions: List<CashAppPayPaymentAction>,
    val redirectUri: String? = null,
  ) : ClientEventPayload

  data class UpdateCustomerRequestData(
    val actions: List<CashAppPayPaymentAction>,
    val requestId: String,
  ) : ClientEventPayload
}

sealed interface PayKitEvents {
  sealed interface CreateCustomerRequestEvent {
    data class CreateCustomerRequest(override val data: CreatingCustomerRequestData) :
      DataEvent<CreatingCustomerRequestData>, CreateCustomerRequestEvent

    data class Success(override val data: CustomerResponseData) : DataEvent<CustomerResponseData>,
      CreateCustomerRequestEvent

    data class Error(override val data: Exception) : DataEvent<Exception>,
      CreateCustomerRequestEvent
  }

  sealed interface GetCustomerRequestEvent {
    data class Success(override val data: CustomerResponseData) :
      DataEvent<CustomerResponseData>,
      GetCustomerRequestEvent

    data class Error(override val data: Exception) : DataEvent<Exception>,
      GetCustomerRequestEvent
  }

  sealed interface StartWithExistingCustomerRequestEvent {

    data class Start(override val data: String) : StartWithExistingCustomerRequestEvent,
      DataEvent<String>

    data class Success(override val data: CustomerResponseData) :
      StartWithExistingCustomerRequestEvent,
      DataEvent<CustomerResponseData>

    data class Error(override val data: Exception) : DataEvent<Exception>,
      StartWithExistingCustomerRequestEvent
  }

  sealed interface UpdateCustomerRequestEvent {

    data class UpdateCustomerRequestAction(override val data: UpdateCustomerRequestData) :
      UpdateCustomerRequestEvent, DataEvent<UpdateCustomerRequestData>

    data class Success(override val data: CustomerResponseData) : UpdateCustomerRequestEvent,
      DataEvent<CustomerResponseData>

    data class Error(override val data: Exception) : DataEvent<Exception>,
      UpdateCustomerRequestEvent
  }

  data class Authorize(override val data: CustomerResponseData) : DataEvent<CustomerResponseData>
  object AuthorizeUsingExistingData : Event
  object DeepLinkSuccess : Event

  data class DeepLinkError(override val data: Exception) : DataEvent<Exception>

  sealed interface InputEvents {
    data class IllegalArguments(override val data: CashAppPayIntegrationException) :
      DataEvent<CashAppPayIntegrationException>
  }
}


