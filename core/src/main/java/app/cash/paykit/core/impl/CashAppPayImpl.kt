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
package app.cash.paykit.core.impl

import android.util.Log
import androidx.annotation.WorkerThread
import app.cash.paykit.core.BuildConfig
import app.cash.paykit.core.CashAppPay
import app.cash.paykit.core.CashAppPayLifecycleObserver
import app.cash.paykit.core.CashAppPayListener
import app.cash.paykit.core.CashAppPayState
import app.cash.paykit.core.CashAppPayState.Approved
import app.cash.paykit.core.CashAppPayState.Authorizing
import app.cash.paykit.core.CashAppPayState.CashAppPayExceptionState
import app.cash.paykit.core.CashAppPayState.CreatingCustomerRequest
import app.cash.paykit.core.CashAppPayState.Declined
import app.cash.paykit.core.CashAppPayState.NotStarted
import app.cash.paykit.core.CashAppPayState.PollingTransactionStatus
import app.cash.paykit.core.CashAppPayState.ReadyToAuthorize
import app.cash.paykit.core.CashAppPayState.RetrievingExistingCustomerRequest
import app.cash.paykit.core.CashAppPayState.UpdatingCustomerRequest
import app.cash.paykit.core.CashAppPayStateMachine
import app.cash.paykit.core.NetworkManager
import app.cash.paykit.core.PayKitEvents
import app.cash.paykit.core.PayKitMachineStates
import app.cash.paykit.core.PayKitMachineStates.Authorizing.DeepLinking
import app.cash.paykit.core.PayKitMachineStates.Authorizing.Polling
import app.cash.paykit.core.PayKitMachineStates.DecidedState
import app.cash.paykit.core.PayKitMachineStates.ErrorState.ExceptionState
import app.cash.paykit.core.PayKitMachineStates.StartingWithExistingRequest
import app.cash.paykit.core.analytics.PayKitAnalyticsEventDispatcher
import app.cash.paykit.core.exceptions.CashAppPayIntegrationException
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction
import app.cash.paykit.core.utils.orElse
import ru.nsk.kstatemachine.activeStates
import ru.nsk.kstatemachine.onStateEntry
import ru.nsk.kstatemachine.onStateExit
import ru.nsk.kstatemachine.onStateFinished
import ru.nsk.kstatemachine.onTransitionComplete
import ru.nsk.kstatemachine.onTransitionTriggered
import ru.nsk.kstatemachine.processEventBlocking
import ru.nsk.kstatemachine.startBlocking

/**
 * @param clientId Client Identifier that should be provided by Cash PayKit integration.
 * @param useSandboxEnvironment Specify what development environment should be used.
 */
internal class CashAppCashAppPayImpl(
  private val clientId: String,
  private val networkManager: NetworkManager,
  private val analyticsEventDispatcher: PayKitAnalyticsEventDispatcher,
  private val payKitLifecycleListener: CashAppPayLifecycleObserver,
  private val useSandboxEnvironment: Boolean = false,
  initialState: CashAppPayState = NotStarted,
  initialCustomerResponseData: CustomerResponseData? = null,
) : CashAppPay, CashAppPayLifecycleListener {

  private var callbackListener: CashAppPayListener? = null

  // TODO pass in initial state
  private val stateMachine = CashAppPayStateMachine(clientId, networkManager)

  init {
    // Register for process lifecycle updates.
    payKitLifecycleListener.register(this)
    analyticsEventDispatcher.sdkInitialized()

    Thread {
      with(stateMachine.payKitMachine) {
        startBlocking()
        onTransitionTriggered {
          // Listen to all transitions in one place
          // instead of listening to each transition separately
          Log.d(
            name,
            "Transition triggered from ${it.transition.sourceState} to ${it.direction.targetState} " +
              "on ${it.event} with argument: ${it.argument}"
          )
        }
        onTransitionComplete { transitionParams, activeStates ->
          Log.d(
            name,
            "Transition complete from ${transitionParams.transition.sourceState}, active states: $activeStates"
          )

          val state = activeStates.last() as PayKitMachineStates
          val customerState = when (state) { // return the "deepest" child state
            PayKitMachineStates.NotStarted -> NotStarted
            PayKitMachineStates.CreatingCustomerRequest -> CreatingCustomerRequest
            is PayKitMachineStates.ReadyToAuthorize -> ReadyToAuthorize(stateMachine.context!!.customerResponseData!!)
            is DeepLinking -> Authorizing
            is Polling -> PollingTransactionStatus
            DecidedState.Approved -> Approved(stateMachine.context.customerResponseData!!)
            DecidedState.Declined -> Declined
            is ExceptionState -> CashAppPayExceptionState(error(state.data))
            PayKitMachineStates.UpdatingCustomerRequest -> UpdatingCustomerRequest
            StartingWithExistingRequest -> RetrievingExistingCustomerRequest
          }

          // Or we could do this in the individual state nodes
          analyticsEventDispatcher.genericStateChanged(
            state,
            stateMachine.context.customerResponseData
          )

          // Notify listener of State change.
          callbackListener?.cashAppPayStateDidChange(customerState)
            .orElse {
              logError(
                "State changed to ${customerState.javaClass.simpleName}, but no listeners were notified." +
                  "Make sure that you've used `registerForStateUpdates` to receive PayKit state updates.",
              )
            }

        }

        onStateEntry { state, _ -> Log.d(name, "Entered state $state") }
        onStateExit { state, _ -> Log.d(name, "Exit state $state") }
        onStateFinished { state, _ -> Log.d(name, "State finished $state") }
      }
    }.start()
  }

  @Throws(IllegalStateException::class)
  override fun createCustomerRequest(paymentAction: CashAppPayPaymentAction, redirectUri: String?) {
    createCustomerRequest(listOf(paymentAction), redirectUri)
  }

  /**
   * Create customer request given a [CashAppPayPaymentAction].
   * Must be called from a background thread.
   *
   * @param paymentActions A wrapper class that contains all of the necessary ingredients for building a customer request.
   *                      Look at [PayKitPaymentAction] for more details.
   */
  @WorkerThread
  @Throws(IllegalStateException::class)
  override fun createCustomerRequest(
    paymentActions: List<CashAppPayPaymentAction>,
    redirectUri: String?
  ) {
    enforceRegisteredStateUpdatesListener()
    enforceMachineRunning()

    // Validate [paymentActions] is not empty.
    if (paymentActions.isEmpty()) {
      val exceptionText = "paymentAction should not be empty"
      throw IllegalArgumentException(exceptionText)
    }

    stateMachine.payKitMachine.processEventBlocking(
      PayKitEvents.CreateCustomerRequest(
        Pair(
          paymentActions,
          redirectUri
        )
      )
    )
  }

  @Throws(IllegalStateException::class)
  override fun updateCustomerRequest(requestId: String, paymentAction: CashAppPayPaymentAction) {
    updateCustomerRequest(requestId, listOf(paymentAction))
  }

  /**
   * Update an existing customer request given its [requestId] an the updated definitions contained within [CashAppPayPaymentAction].
   * Must be called from a background thread.
   *
   * @param requestId ID of the request we intent do update.
   * @param paymentActions A wrapper class that contains all of the necessary ingredients for building a customer request.
   *                      Look at [PayKitPaymentAction] for more details.
   */
  @WorkerThread
  @Throws(IllegalArgumentException::class, IllegalStateException::class)
  override fun updateCustomerRequest(
    requestId: String,
    paymentActions: List<CashAppPayPaymentAction>,
  ) {
    enforceRegisteredStateUpdatesListener()
    enforceMachineRunning()

    // Validate [paymentActions] is not empty.
    if (paymentActions.isEmpty()) {
      val exceptionText = "paymentAction should not be empty"
      throw IllegalArgumentException(exceptionText)
    }

    // TODO change to when extension fun
    if (stateMachine.payKitMachine.activeStates()
        .any { it is PayKitMachineStates.Authorizing || it is PayKitMachineStates.ReadyToAuthorize }
    ) {
      stateMachine.payKitMachine.processEventBlocking(
        PayKitEvents.UpdateCustomerRequestEvent.UpdateCustomerRequestAction(paymentActions to requestId)
      )
    } else {
      // TODO should we be including the customer response data in these exceptions, so they can do something with it?
      // stateMachine.payKitMachine.processEventBlocking(
      //   PayKitEvents.InputEvents.IllegalArguments(CashAppPayIntegrationException("Unable to update customer request. Not in the correct state"))
      // )
      val exceptionText = "Unable to update customer request. Not in the correct state"
      /*stateMachine.payKitMachine.processEventBlocking(
        PayKitEvents.InputEvents.IllegalArguments(CashAppPayIntegrationException(exceptionText))
      )*/
      throw IllegalArgumentException(exceptionText)
    }
  }

  @WorkerThread
  @Throws(IllegalStateException::class)
  override fun startWithExistingCustomerRequest(requestId: String) {
    enforceRegisteredStateUpdatesListener()
    enforceMachineRunning()

    stateMachine.payKitMachine.processEventBlocking(
      PayKitEvents.StartWithExistingCustomerRequestEvent.Start(requestId)
    )
  }

  /**
   * Authorize a customer request. This function must be called AFTER `createCustomerRequest`.
   * Not doing so will result in an Exception in sandbox mode, and a silent error log in production.
   */
  @Throws(
    IllegalArgumentException::class,
    CashAppPayIntegrationException::class,
    IllegalStateException::class
  )
  override fun authorizeCustomerRequest() {

    enforceMachineRunning()

    if (stateMachine.payKitMachine.activeStates()
        .none { it is PayKitMachineStates.Authorizing || it is PayKitMachineStates.ReadyToAuthorize }
    ) {
      // TODO we are throwing here... should we throw in other methods?
      logAndSoftCrash(
        CashAppPayIntegrationException(
          "State machine is not ready to authorize",
        ),
      )
      return
    }

    authorizeCustomerRequest(stateMachine.context.customerResponseData!!)
  }

  /**
   * Authorize a customer request with a previously created `customerData`.
   * This function will set this SDK instance internal state to the `customerData` provided here as a function parameter.
   *
   */
  @Throws(IllegalArgumentException::class, RuntimeException::class, IllegalStateException::class)
  override fun authorizeCustomerRequest(
    customerData: CustomerResponseData,
  ) {
    enforceRegisteredStateUpdatesListener()

    enforceMachineRunning()

    if (customerData.authFlowTriggers?.mobileUrl.isNullOrEmpty()) {
      throw IllegalArgumentException("customerData is missing redirect url")
    }

    stateMachine.payKitMachine.processEventBlocking(
      PayKitEvents.Authorize(customerData)
    )
  }

  /**
   *  Register a [CashAppPayListener] to receive PayKit callbacks.
   */
  override fun registerForStateUpdates(listener: CashAppPayListener) {
    callbackListener = listener
    analyticsEventDispatcher.eventListenerAdded()
  }

  /**
   *  Unregister any previously registered [CashAppPayListener] from PayKit updates.
   */
  override fun unregisterFromStateUpdates() {
    callbackListener = null
    payKitLifecycleListener.unregister(this)
    analyticsEventDispatcher.eventListenerRemoved()
    analyticsEventDispatcher.shutdown()
  }

  private fun enforceRegisteredStateUpdatesListener() {
    if (callbackListener == null) {
      logAndSoftCrash(
        CashAppPayIntegrationException(
          "Shouldn't call this function before registering for state updates via `registerForStateUpdates`.",
        ),
      )
    }
  }

  private fun logError(errorMessage: String) {
    Log.e("PayKit", errorMessage)
  }

  /**
   * This function will log in production, additionally it will throw an exception in sandbox or debug mode.
   */
  @Throws
  private fun logAndSoftCrash(exception: Exception) {
    logError("Error occurred. E.: $exception")
    if (useSandboxEnvironment || BuildConfig.DEBUG) {
      throw exception
    }
  }

  /**
   * This function will throw the provided [exception] during development, or change the SDK state to [CashAppPayExceptionState] otherwise.
   */
  @Throws
  private fun softCrashOrStateException(exception: Exception): CashAppPayExceptionState {
    logError("Error occurred. E.: $exception")
    if (useSandboxEnvironment || BuildConfig.DEBUG) {
      throw exception
    }
    return CashAppPayExceptionState(exception)
  }

  private fun enforceMachineRunning() {
    if (stateMachine.payKitMachine.isFinished) {
      val exceptionText = "This SDK instance has already finished. Please start a new one."
      throw IllegalStateException(exceptionText)
    }
  }

  /**
   * Lifecycle callbacks.
   */

  override fun onApplicationForegrounded() {
    logError("onApplicationForegrounded")
    // TODO send message into machine so it starts polling
  }

  override fun onApplicationBackgrounded() {
    logError("onApplicationBackgrounded")
  }
}
