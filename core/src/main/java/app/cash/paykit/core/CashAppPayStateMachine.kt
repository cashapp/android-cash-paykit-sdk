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

package app.cash.paykit.core

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.cash.paykit.core.PayKitEvents.Authorize
import app.cash.paykit.core.PayKitEvents.CreateCustomerRequest
import app.cash.paykit.core.PayKitEvents.DeepLinkError
import app.cash.paykit.core.PayKitEvents.DeepLinkSuccess
import app.cash.paykit.core.PayKitEvents.GetCustomerRequestEvent
import app.cash.paykit.core.PayKitEvents.InputEvents.IllegalArguments
import app.cash.paykit.core.PayKitEvents.UpdateCustomerRequestEvent
import app.cash.paykit.core.PayKitEvents.UpdateCustomerRequestEvent.UpdateCustomerRequestAction
import app.cash.paykit.core.PayKitMachineContext.CreateRequestParams
import app.cash.paykit.core.PayKitMachineStates.Authorizing
import app.cash.paykit.core.PayKitMachineStates.DecidedState.Approved
import app.cash.paykit.core.PayKitMachineStates.DecidedState.Declined
import app.cash.paykit.core.PayKitMachineStates.ErrorState.ExceptionState
import app.cash.paykit.core.PayKitMachineStates.ReadyToAuthorize
import app.cash.paykit.core.PayKitMachineStates.UpdatingCustomerRequest
import app.cash.paykit.core.android.ApplicationContextHolder
import app.cash.paykit.core.android.ApplicationContextHolder.init
import app.cash.paykit.core.exceptions.CashAppPayIntegrationException
import app.cash.paykit.core.models.common.NetworkResult.Failure
import app.cash.paykit.core.models.common.NetworkResult.Success
import app.cash.paykit.core.models.request.CustomerRequestData
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.response.STATUS_DECLINED
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction
import ru.nsk.kstatemachine.DataEvent
import ru.nsk.kstatemachine.DefaultDataState
import ru.nsk.kstatemachine.DefaultFinalState
import ru.nsk.kstatemachine.DefaultState
import ru.nsk.kstatemachine.Event
import ru.nsk.kstatemachine.State
import ru.nsk.kstatemachine.StateMachine
import ru.nsk.kstatemachine.addFinalState
import ru.nsk.kstatemachine.addInitialState
import ru.nsk.kstatemachine.createStdLibStateMachine
import ru.nsk.kstatemachine.dataTransition
import ru.nsk.kstatemachine.defaultDataExtractor
import ru.nsk.kstatemachine.noTransition
import ru.nsk.kstatemachine.onEntry
import ru.nsk.kstatemachine.onExit
import ru.nsk.kstatemachine.onTriggered
import ru.nsk.kstatemachine.processEventBlocking
import ru.nsk.kstatemachine.state
import ru.nsk.kstatemachine.targetState
import ru.nsk.kstatemachine.transition
import ru.nsk.kstatemachine.transitionConditionally
import ru.nsk.kstatemachine.visitors.exportToPlantUmlBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

typealias CreatingCustomerRequestDataState = Pair<List<CashAppPayPaymentAction>, String?>

/**
 * Mutable contextual data for use in the state machine.
 *
 * Acts as a sort of redux store in the state machine
 */
data class PayKitMachineContext(
  val createRequestParams: CreateRequestParams? = null,
  val customerRequestData: CustomerRequestData? = null,
  val customerResponseData: CustomerResponseData? = null,
) {

  data class CreateRequestParams(
    val actions: List<CashAppPayPaymentAction>,
    val redirectUri: String? = null
  )
}

sealed interface PayKitMachineStates {

  object NotStarted : PayKitMachineStates, DefaultState("NotStarted")
  object CreatingCustomerRequest : PayKitMachineStates, DefaultState("CreatingCustomerRequest")

  object ReadyToAuthorize : PayKitMachineStates, DefaultState("ReadyToAuthorize")

  sealed interface Authorizing : PayKitMachineStates {
    // object Parent : Authorizing, DefaultState("Authorizing")
    object DeepLinking : Authorizing, DefaultState("DeepLinking")
    object Polling : Authorizing, DefaultState("Polling")
  }

  object UpdatingCustomerRequest : PayKitMachineStates,
    DefaultDataState<UpdateCustomerRequestActionData>(
      "UpdatingCustomerRequest",
      dataExtractor = defaultDataExtractor()
    )

  sealed interface ErrorState : PayKitMachineStates {
    object ExceptionState : ErrorState,
      DefaultDataState<Exception>(
        "ExceptionState",
        dataExtractor = defaultDataExtractor()
      )
  }

  sealed interface DecidedState : PayKitMachineStates {
    object Approved : DecidedState, DefaultFinalState("Approved")
    object Declined : DecidedState, DefaultFinalState("Declined")
  }
}

internal class CashAppPayStateMachine constructor(
  private val clientId: String,
  private val networkManager: NetworkManager,
) {

  var context: PayKitMachineContext = PayKitMachineContext()
    set(value) {
      field = value
      Log.d(payKitMachine.name, "Updating context: $value")
    }

  private fun UpdatingCustomerRequest.updateCustomerRequestOnEntry() {

    Log.d("CRAIG", "updateCustomerRequestOnEntry")
    val thread = Thread {
      try {
        val networkResult = networkManager.updateCustomerRequest(
          clientId,
          data.second,
          data.first
        )
        when (networkResult) {
          is Failure -> {
            machine.processEventBlocking(UpdateCustomerRequestEvent.Error(networkResult.exception))
          }

          is Success -> {
            val customerResponseData = networkResult.data.customerResponseData

            context = context.copy(
              customerResponseData = customerResponseData
            )
            machine.processEventBlocking(UpdateCustomerRequestEvent.Success(customerResponseData))
          }
        }
      } catch (e: InterruptedException) {
      }
    }.apply {
      name = "update-request-thread"
    }

    onEntry {
      Log.d("CRAIG", "onEntry - start")
      thread.start()
    }
    onExit {
      Log.d("CRAIG", "onExit - interrupt")

      thread.interrupt()
    }
  }

  private fun State.poll(interval: Duration) {

    var thread: Thread? = null

    onEntry {
      thread = Thread {
        try {
          while (true) {
            Thread.sleep(interval.inWholeMilliseconds)
            val networkResult = networkManager.retrieveUpdatedRequestData(
              clientId,
              context.customerResponseData!!.id
            )
            when (networkResult) {
              is Failure -> {
                machine.processEventBlocking(GetCustomerRequestEvent.Error(networkResult.exception))
              }

              is Success -> {
                val customerResponseData = networkResult.data.customerResponseData

                context = context.copy(
                  customerResponseData = customerResponseData
                )
                machine.processEventBlocking(GetCustomerRequestEvent.Success(customerResponseData))
              }
            }
          }
        } catch (e: InterruptedException) {
          Log.w("CRAIG", "InterruptedException getting customer request", e)
        }

      }.apply {
        name = "polling-thread"
        start()
      }
    }
    onExit {
      thread?.interrupt()
      Log.e("CRAIG", "Stopping thread!!!")
    }
  }

  val payKitMachine = createStdLibStateMachine(
    name = "PayKit",
    start = false,
  ) {
    logger = StateMachine.Logger { Log.d(name, it()) }
    /*listenerExceptionHandler = StateMachine.ListenerExceptionHandler {
      Log.e("$name-Listener-error-Handler", "Exception: ${it}")
    }

    ignoredEventHandler = StateMachine.IgnoredEventHandler {
      Log.w(name, "unexpected ${it.event}")
    }

    ignoredEventHandler = StateMachine.IgnoredEventHandler {
      Log.w(name, "ignored event ${it.event}")
    }*/

    addState(ExceptionState) {

    }

    dataTransition<IllegalArguments, Exception> {
      targetState = ExceptionState
    }

    addState(UpdatingCustomerRequest) {
      this@addState.updateCustomerRequestOnEntry()

      dataTransition<UpdateCustomerRequestEvent.Error, Exception> {
        targetState = ExceptionState
      }

      //TODO add back in data state
      transition<UpdateCustomerRequestEvent.Success> {
        //TODO history state?
        // calculate target state? (either ReadyToAuth or another?)
        targetState = ReadyToAuthorize
      }
    }

    dataTransition<UpdateCustomerRequestAction, Pair<List<CashAppPayPaymentAction>, String>> {
      targetState = UpdatingCustomerRequest
    }

    val authorizingState = state("Authorizing") {
      transitionConditionally<GetCustomerRequestEvent.Success> {
        direction = {
          if (context.customerResponseData?.grants?.isNotEmpty() == true) {
            targetState(Approved)
          } else if (context.customerResponseData?.status.equals(STATUS_DECLINED)) {
            targetState(Declined)
          } else {
            noTransition()
          }
        }
      }

      addState(Authorizing.Polling) {
        poll(2.seconds)
      }

      addInitialState(Authorizing.DeepLinking) {
        // TODO abstract this to a worker
        onEntry {
          Thread {
            // Open Mobile URL provided by backend response.
            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.data = try {
              Uri.parse(context.customerResponseData!!.authFlowTriggers?.mobileUrl)
            } catch (error: NullPointerException) {
              machine.processEventBlocking(DeepLinkError(IllegalArgumentException("Cannot parse redirect url")))
              return@Thread
            }

            try {
              ApplicationContextHolder.applicationContext.startActivity(intent)
              machine.processEventBlocking(DeepLinkSuccess)
            } catch (activityNotFoundException: ActivityNotFoundException) {
              machine.processEventBlocking(DeepLinkError(CashAppPayIntegrationException("Unable to open mobileUrl: ${context.customerResponseData!!.authFlowTriggers?.mobileUrl}")))
            }

          }.start()
        }
        transition<DeepLinkSuccess> {
          targetState = Authorizing.Polling
        }
        dataTransition<DeepLinkError, Exception> {
          targetState = ExceptionState
        }
      }
    }

    addState(ReadyToAuthorize) {
      poll(10.seconds)

      transition<Authorize> {
        guard = { context.customerResponseData?.id != "bad" }
        targetState = authorizingState
      }
    }

    val creatingCustomerRequest =
      addState(PayKitMachineStates.CreatingCustomerRequest) {
        onEntry {
          try {
            when (val networkResult =
              networkManager.createCustomerRequest(
                clientId,
                context.createRequestParams!!.actions,
                context.createRequestParams!!.redirectUri,
              )
            ) {
              is Failure -> {
                machine.processEventBlocking(CreateCustomerRequest.Error(networkResult.exception))
              }

              is Success -> {
                val customerResponseData = networkResult.data.customerResponseData
                context = context.copy(customerResponseData = customerResponseData)
                machine.processEventBlocking(CreateCustomerRequest.Success(customerResponseData))
              }
            }
          } catch (e: Exception) {
            machine.processEventBlocking(CreateCustomerRequest.Error(e))
          }
        }
        onExit {
          // TODO cancel network request?
        }
        transition<CreateCustomerRequest.Success> {
          targetState = ReadyToAuthorize
        }
        dataTransition<CreateCustomerRequest.Error, Exception> {
          targetState = ExceptionState
        }
      }

    addInitialState(PayKitMachineStates.NotStarted) {
      transition<CreateCustomerRequest> {
        targetState = creatingCustomerRequest
        onTriggered {
          context = context.copy(
            createRequestParams = CreateRequestParams(
              it.event.data.first,
              it.event.data.second
            )
          )
        }
      }
    }

    addFinalState(Approved) {
    }

    addFinalState(Declined) {
    }
  }

  init {
    // www.plantuml.com/plantuml/png/nPRDIWCn58NtynHP2tq5QMatWeYqxiJ5q1ocmVp4v4QeuhlRknWOQBizcULilY_9ERbPLncBjE27axCm25dEng8UYylYKXmCuRiu2CmcvvA-OOd87OxUHIhooNjCez-KgGL4gGVzozpb9gTKt8-Ba_cTkIJsQ_B-mYLJTq1-Tl6JIQQ1tXy-p6hrs6EHIwOwK8_tZrYPFKPxVajpSq14Rmj6SN7QjYefeEpnrL25QF2IvA0ZWdH93iJIaDTsbIDrMDx738bxYLANAy7UGls6vI8QAjArX_ExI4KqkWpe_3AYGcyVzyA0Fz9k1t2DQ5lm_m00
    Log.d("CRAIG", payKitMachine.exportToPlantUmlBlocking())
  }
}

typealias UpdateCustomerRequestActionData = Pair<List<CashAppPayPaymentAction>, String>

sealed interface PayKitEvents {
  data class CreateCustomerRequest(
    override val data: CreatingCustomerRequestDataState,
  ) : DataEvent<CreatingCustomerRequestDataState> {
    data class Success(override val data: CustomerResponseData) :
      DataEvent<CustomerResponseData>

    data class Error(override val data: Exception) : DataEvent<Exception>
  }

  sealed interface GetCustomerRequestEvent {
    data class Success(override val data: CustomerResponseData) : DataEvent<CustomerResponseData>,
      GetCustomerRequestEvent

    data class Error(override val data: Exception) : DataEvent<Exception>, GetCustomerRequestEvent
  }

  sealed interface UpdateCustomerRequestEvent {

    data class UpdateCustomerRequestAction(override val data: UpdateCustomerRequestActionData) :
      UpdateCustomerRequestEvent, DataEvent<UpdateCustomerRequestActionData>

    data class Success(override val data: CustomerResponseData) : UpdateCustomerRequestEvent,
      DataEvent<CustomerResponseData>

    data class Error(override val data: Exception) : DataEvent<Exception>,
      UpdateCustomerRequestEvent
  }

  data class Authorize(override val data: CustomerResponseData) : DataEvent<CustomerResponseData>
  object DeepLinkSuccess : Event

  data class DeepLinkError(override val data: Exception) : DataEvent<Exception>

  sealed interface InputEvents {
    data class IllegalArguments(override val data: CashAppPayIntegrationException) :
      DataEvent<CashAppPayIntegrationException>
  }
}


