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
import app.cash.paykit.core.state.PayKitEvents.CreateCustomerRequest
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
import ru.nsk.kstatemachine.ConditionalTransitionBuilder
import ru.nsk.kstatemachine.DataState
import ru.nsk.kstatemachine.EventAndArgument
import ru.nsk.kstatemachine.IState
import ru.nsk.kstatemachine.StateMachine
import ru.nsk.kstatemachine.TransitionDirection
import ru.nsk.kstatemachine.TransitionType
import ru.nsk.kstatemachine.addFinalState
import ru.nsk.kstatemachine.addInitialState
import ru.nsk.kstatemachine.createStdLibStateMachine
import ru.nsk.kstatemachine.dataState
import ru.nsk.kstatemachine.dataTransition
import ru.nsk.kstatemachine.dataTransitionOn
import ru.nsk.kstatemachine.noTransition
import ru.nsk.kstatemachine.onTriggered
import ru.nsk.kstatemachine.processEventBlocking
import ru.nsk.kstatemachine.stay
import ru.nsk.kstatemachine.targetState
import ru.nsk.kstatemachine.transition
import ru.nsk.kstatemachine.transitionConditionally
import ru.nsk.kstatemachine.visitors.exportToPlantUmlBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


internal data class PayKitMachine(
  private val worker: PayKitWorker,
) {
  val stateMachine = createPayKitMachine().apply {
//    Log.d("CRAIG", exportToPlantUmlBlocking())
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

      dataTransition<IllegalArguments, Exception> {
        targetState = ExceptionState
      }

      // This captures our polling get events. (in both Polling and ReadyToAuth states)
      // TODO also listen to Create events? may want to de-scope this into a child scope.
//      dataTransitionOn<GetCustomerRequestEvent.Success, CustomerResponseData> {

//      }


      addState(UpdatingCustomerRequest) {
        worker.updateCustomerRequest(this)

        dataTransition<UpdateCustomerRequestEvent.Error, Exception> {
          targetState = ExceptionState
        }

        dataTransition<UpdateCustomerRequestEvent.Success, CustomerResponseData> {
          targetState = ReadyToAuthorize
        }
      }

      addState(StartingWithExistingRequest) {
        worker.startWithExistingRequest(this)

        dataTransition<StartWithExistingCustomerRequestEvent.Error, Exception> {
          targetState = ExceptionState
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

      dataTransition<UpdateCustomerRequestAction, UpdateCustomerRequestData> {
        // TODO guard this? or should the public api protect us here?
        // guard = {machine.activeStates()}
        targetState = UpdatingCustomerRequest
      }

      val authorizingState = dataState<CustomerResponseData>(name = "Authorizing") {

          transitionConditionally<GetCustomerRequestEvent.Success> {
              onTriggered {
                  Log.w("CRAIG", "transitionConditionally triggered from within Authorizing event=${it.event.data.authFlowTriggers}, direction= ${it.direction.targetState}")
              }
              direction = {
                  with(event.data) {
                      if (grants?.isNotEmpty() == true && status == STATUS_APPROVED
                      ) {
                          targetState(Approved)
                      } else if (status == STATUS_DECLINED) {
                          targetState(Declined)
                      } else {
                          Log.w("CRAIG", "transitionConditionally triggered from within Polling ${event.data.authFlowTriggers}")

                          targetState(this@dataState)// This updates the state data with the new auth triggers
                      }
                  }
              }
          }

        addState(Polling) {
          worker.poll(this, this@dataState, 2.seconds)

          // Let the customer deep link again
          transition<Authorize> {
            targetState = DeepLinking
          }
            // PROBLEM: this transition uses the state from the auth state, not the polling state
          transition<AuthorizeUsingExistingData> {
            targetState = DeepLinking
          }
        }
        addInitialState(DeepLinking) {

          worker.deepLinkToCashApp(this, this@dataState)

          transition<DeepLinkSuccess> {
            targetState = Polling
          }
          dataTransition<DeepLinkError, Exception> {
            targetState = ExceptionState
          }
        }
      }

      addState(ReadyToAuthorize) {
        worker.poll(this, this@addState, 20.seconds)

        dataTransition<Authorize, CustomerResponseData> {
          // guard = { event.data.id != "bad" } //  We could guard this transition if the CustomerRequest is invalid. We most likely want to validate this in the public API rather in the machine events
          targetState = authorizingState
        }

        transition<AuthorizeUsingExistingData> {
          onTriggered {
            stateMachine.processEventBlocking(Authorize(this@addState.data))
          }
        }

          transitionConditionally<GetCustomerRequestEvent.Success> {
//              onTriggered {
//                  Log.w("CRAIG", "transitionConditionally triggered from within ReadyToAuthorize event=${it.event.data.authFlowTriggers}, direction= ${it.direction.targetState}")
//              }
//              type = TransitionType.EXTERNAL // this causes the workers to restart
              direction =  {
                  with(event.data) {
                      if (grants?.isNotEmpty() == true && status == STATUS_APPROVED
                      ) {
                          targetState(Approved)
                      } else if (status == STATUS_DECLINED) {
                          targetState(Declined)
                      } else {
//                          Log.w("CRAIG", "staying put within ReadyToAuthorize ${event.data.authFlowTriggers}")
                          targetState(this@addState)// This updates the state data with the new auth triggers
                      }
                  }
              }
          }


      }

      val creatingCustomerRequest =
        addState(CreatingCustomerRequest) {
          worker.createCustomerRequest(this)

          dataTransition<CreateCustomerRequest.Success, CustomerResponseData> {
            targetState = ReadyToAuthorize
          }
          dataTransition<CreateCustomerRequest.Error, Exception> {
            targetState = ExceptionState
          }
        }

      addInitialState(NotStarted) {

        // TODO should we allow clients to create a customer request while in any state?
        dataTransition<CreateCustomerRequest, CreatingCustomerRequestData> {
          targetState = creatingCustomerRequest
        }

        dataTransition<StartWithExistingCustomerRequestEvent.Start, String> {
          // TODO guard this? or should the public api protect us here?
          // guard = {machine.activeStates()}
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
