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

import android.util.Log
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.response.STATUS_APPROVED
import app.cash.paykit.core.models.response.STATUS_DECLINED
import app.cash.paykit.core.models.response.STATUS_PENDING
import app.cash.paykit.core.models.response.STATUS_PROCESSING
import app.cash.paykit.core.state.ClientEventPayload.CreatingCustomerRequestData
import app.cash.paykit.core.state.ClientEventPayload.UpdateCustomerRequestData
import app.cash.paykit.core.state.PayKitEvents.Authorize
import app.cash.paykit.core.state.PayKitEvents.AuthorizeUsingExistingData
import app.cash.paykit.core.state.PayKitEvents.CreateCustomerRequestEvent
import app.cash.paykit.core.state.PayKitEvents.DeepLinkError
import app.cash.paykit.core.state.PayKitEvents.DeepLinkSuccess
import app.cash.paykit.core.state.PayKitEvents.GetCustomerRequestEvent
import app.cash.paykit.core.state.PayKitEvents.InputEvents.IllegalArguments
import app.cash.paykit.core.state.PayKitEvents.StartWithExistingCustomerRequestEvent
import app.cash.paykit.core.state.PayKitEvents.UpdateCustomerRequestEvent
import app.cash.paykit.core.state.PayKitEvents.UpdateCustomerRequestEvent.UpdateCustomerRequestAction
import app.cash.paykit.core.state.PayKitMachineStates.Authorizing.DeepLinking
import app.cash.paykit.core.state.PayKitMachineStates.Authorizing.Polling
import app.cash.paykit.core.state.PayKitMachineStates.CreatingCustomerRequest
import app.cash.paykit.core.state.PayKitMachineStates.DecidedState.Approved
import app.cash.paykit.core.state.PayKitMachineStates.DecidedState.Declined
import app.cash.paykit.core.state.PayKitMachineStates.ErrorState.ExceptionState
import app.cash.paykit.core.state.PayKitMachineStates.NotStarted
import app.cash.paykit.core.state.PayKitMachineStates.ReadyToAuthorize
import app.cash.paykit.core.state.PayKitMachineStates.StartingWithExistingRequest
import app.cash.paykit.core.state.PayKitMachineStates.UpdatingCustomerRequest
import ru.nsk.kstatemachine.State
import ru.nsk.kstatemachine.StateMachine
import ru.nsk.kstatemachine.addFinalState
import ru.nsk.kstatemachine.addInitialState
import ru.nsk.kstatemachine.createStdLibStateMachine
import ru.nsk.kstatemachine.onTriggered
import ru.nsk.kstatemachine.processEventBlocking
import ru.nsk.kstatemachine.state
import ru.nsk.kstatemachine.stay
import ru.nsk.kstatemachine.targetState
import ru.nsk.kstatemachine.transition
import ru.nsk.kstatemachine.transitionConditionally
import ru.nsk.kstatemachine.visitors.exportToPlantUmlBlocking
import kotlin.time.Duration.Companion.seconds

/** Mutable context ("extended state") used by the state machine */
data class PayKitContext(
  var createCustomerRequestData: CreatingCustomerRequestData? = null,
  var updateCustomerRequestData: UpdateCustomerRequestData? = null,
  var startWithExistingId: String? = null,

  /** The latest customer request data */
  var customerResponseData: CustomerResponseData? = null,

  /** the exception */
  var error: Exception? = null,
)

internal data class PayKitMachine(
  private val worker: PayKitWorker,
) {
  val context = PayKitContext()
  val stateMachine = createPayKitMachine().apply {
    Log.d("CRAIG", exportToPlantUmlBlocking())
  }

  /**
   * Used in both [ReadyToAuthorize] and Authorizing
   */
  private fun State.onGetSuccessUpdateContextAndTerminal() {

    transitionConditionally<GetCustomerRequestEvent.Success> {
      onTriggered {
        // store the latest CR in the context
        context.customerResponseData = it.event.data
      }
      direction = {
        with(event.data) {
          if (grants?.isNotEmpty() == true && status == STATUS_APPROVED
          ) {
            targetState(Approved)
          } else if (status == STATUS_DECLINED) {
            targetState(Declined)
          } else {
            stay()
          }
        }
      }
    }
  }

  private fun createPayKitMachine(): StateMachine {
    return createStdLibStateMachine(
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

      transition<IllegalArguments> {
        targetState = ExceptionState
        onTriggered {
          context.error = it.event.data // TODO clear this when it is complete?
        }
      }


      addState(UpdatingCustomerRequest) {
        worker.updateCustomerRequest(this, context)

        transition<UpdateCustomerRequestEvent.Error> {
          targetState = ExceptionState
          onTriggered {
            context.error = it.event.data // TODO clear this when it is complete?
          }
        }

        transition<UpdateCustomerRequestEvent.Success> {
          targetState = ReadyToAuthorize
        }
      }

      addState(StartingWithExistingRequest) {
        worker.startWithExistingRequest(this, context)

        transition<StartWithExistingCustomerRequestEvent.Error> {
          targetState = ExceptionState
          onTriggered {
            context.error = it.event.data // TODO clear this when it is complete?
          }
        }

        transitionConditionally<StartWithExistingCustomerRequestEvent.Success> {
          direction = {
            if (event.data.grants?.isNotEmpty() == true && event.data.status == STATUS_APPROVED) {
              targetState(Approved)
            } else if (event.data.status == STATUS_DECLINED) {
              targetState(Declined)
            } else if (event.data.status == STATUS_PROCESSING) {
              targetState(Polling)
            } else if (event.data.status == STATUS_PENDING) {
              targetState(ReadyToAuthorize)
            } else {
              // TODO don't throw, but put the SDK in an "Internal Error" State and ensure that this event makes it to ES2.
              throw error("unknown state")
            }
          }
        }
      }

      // TODO only allow this in certain states...
      transition<UpdateCustomerRequestAction> {
        // TODO guard this? or should the public api protect us here?
        // guard = {machine.activeStates()}
        targetState = UpdatingCustomerRequest
        onTriggered {
          // check(it.argument is CustomerResponseData) { "bad data returned" }
          Log.w(
            "CRAIG",
            "UpdateCustomerRequestAction event=${it.event.data}"
          )
          // context.customerResponseData = it.argument // old way
          context.updateCustomerRequestData = it.event.data // TODO clear this when it is complete?
        }
      }

      val authorizingState = state(name = "Authorizing") {

        onGetSuccessUpdateContextAndTerminal()

        addState(Polling) {
          worker.poll(this, context, 2.seconds)

          // Let the customer deep link again
          transition<Authorize> {
            targetState = DeepLinking
          }
          transition<AuthorizeUsingExistingData> {
            targetState = DeepLinking
          }
        }

        addInitialState(DeepLinking) {
          worker.deepLinkToCashApp(this, context)

          transition<DeepLinkSuccess> {
            targetState = Polling
          }
          transition<DeepLinkError> {
            targetState = ExceptionState
            onTriggered {
              context.error = it.event.data // TODO clear this when it is complete?
            }
          }
        }
      }

      addState(ReadyToAuthorize) {
        worker.poll(this, context, 20.seconds)

        onGetSuccessUpdateContextAndTerminal()

        // TODO fix event data? include CR here?
        transition<Authorize> {
          // guard = { event.data.id != "bad" } //  We could guard this transition if the CustomerRequest is invalid. We most likely want to validate this in the public API rather in the machine events
          targetState = authorizingState
          onTriggered {
            // Log.w(
            //   "CRAIG",
            //   "transitionConditionally triggered from within ReadyToAuthorize event=${it.event.data.authFlowTriggers}"
            // )
            context.customerResponseData = it.event.data // TODO verify this data?
          }
        }

        transition<AuthorizeUsingExistingData> {
          onTriggered {
            stateMachine.processEventBlocking(Authorize(context.customerResponseData!!))
          }
        }
      }

      val creatingCustomerRequest = addState(CreatingCustomerRequest) {
        worker.createCustomerRequest(this, context)

        transition<CreateCustomerRequestEvent.Success> {
          onTriggered {
            // check(it.argument is CustomerResponseData) { "bad data returned" }
            context.customerResponseData = it.event.data
          }
          targetState = ReadyToAuthorize
        }

        transition<CreateCustomerRequestEvent.Error> {
          onTriggered {
            context.error = it.event.data // TODO clear this when it is complete?
          }
        }
      }

      addInitialState(NotStarted) {

        // TODO should we allow clients to create a customer request while in any state?
        transition<CreateCustomerRequestEvent.CreateCustomerRequest> {
          onTriggered {
            // check(it.argument !== null) { "create customer request data not provided" }
            // it.argument as CreatingCustomerRequestData
            context.createCustomerRequestData = it.event.data
          }
          targetState = creatingCustomerRequest
        }

        transition<StartWithExistingCustomerRequestEvent.Start> {
          onTriggered {
            context.startWithExistingId = it.event.data
          }
          targetState = StartingWithExistingRequest
        }
      }

      addFinalState(Approved) {
      }
      addFinalState(Declined) {
      }
      addState(ExceptionState) {
      }
    }
  }
}
