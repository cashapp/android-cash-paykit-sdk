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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import app.cash.paykit.ClientEventPayload.CreatingCustomerRequestData
import app.cash.paykit.ClientEventPayload.UpdateCustomerRequestData
import app.cash.paykit.NativeWorker
import app.cash.paykit.PayKitEvents
import app.cash.paykit.PayKitEvents.Authorize
import app.cash.paykit.PayKitEvents.CreateCustomerRequest
import app.cash.paykit.PayKitMachine
import app.cash.paykit.PayKitMachineStates
import app.cash.paykit.PayKitMachineStates.Authorizing.DeepLinking
import app.cash.paykit.PayKitMachineStates.Authorizing.Polling
import app.cash.paykit.PayKitMachineStates.DecidedState
import app.cash.paykit.PayKitMachineStates.ErrorState.ExceptionState
import app.cash.paykit.PayKitMachineStates.StartingWithExistingRequest
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
import app.cash.paykit.core.NetworkManager
import app.cash.paykit.core.analytics.PayKitAnalyticsEventDispatcher
import app.cash.paykit.core.android.ApplicationContextHolder
import app.cash.paykit.core.android.CAP_TAG
import app.cash.paykit.core.utils.SingleThreadManager
import app.cash.paykit.core.utils.SingleThreadManagerImpl
import app.cash.paykit.core.utils.orElse
import app.cash.paykit.exceptions.CashAppPayIntegrationException
import app.cash.paykit.logging.CashAppLogger
import app.cash.paykit.models.response.CustomerResponseData
import app.cash.paykit.models.sdk.CashAppPayCurrency.USD
import app.cash.paykit.models.sdk.CashAppPayPaymentAction
import app.cash.paykit.models.sdk.CashAppPayPaymentAction.OneTimeAction
import ru.nsk.kstatemachine.DefaultDataState
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
internal class CashAppPayImpl(
  private val clientId: String,
  private val networkManager: NetworkManager,
  private val analyticsEventDispatcher: PayKitAnalyticsEventDispatcher,
  private val payKitLifecycleListener: CashAppPayLifecycleObserver,
  private val useSandboxEnvironment: Boolean = false,
  private val logger: CashAppLogger,
  private val singleThreadManager: SingleThreadManager = SingleThreadManagerImpl(logger),
  initialState: CashAppPayState = NotStarted,
  initialCustomerResponseData: CustomerResponseData? = null,
) : CashAppPay, CashAppPayLifecycleListener {

  private var callbackListener: CashAppPayListener? = null

  private var customerResponseData: CustomerResponseData? = initialCustomerResponseData

  val nativeWorker = object : NativeWorker {
    override fun deepLink(url: String): Boolean {
      val intent = Intent(Intent.ACTION_VIEW)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.data = try {
        Uri.parse(url)
      } catch (error: NullPointerException) {
        return false
        // throw IllegalArgumentException("Cannot parse redirect url")
      }

      try {
        ApplicationContextHolder.applicationContext.startActivity(intent)
      } catch (activityNotFoundException: ActivityNotFoundException) {
        return false
      }
      return true
    }
  }

  private val payKitMachineWrapper = PayKitMachine(
    nativeWorker = nativeWorker,
    networkingWorker = NativeNetworkWorker(networkManager, clientId)
  ).also {
    // it.stateMachine.startBlocking()
  }

  init {
    // Register for process lifecycle updates.
    payKitLifecycleListener.register(this)
    analyticsEventDispatcher.sdkInitialized()


    Thread {
      with(payKitMachineWrapper.stateMachine) {
        startBlocking()
        onTransitionTriggered {
          // Listen to all transitions in one place
          // instead of listening to each transition separately
          Log.d(
            name,
            "Transition from ${it.transition.sourceState} to ${it.direction.targetState} " +
              "on ${it.event} with argument: ${it.argument}"
          )
          // TODO send drop analytic event
        }
        onTransitionComplete { transitionParams, activeStates ->
          Log.d(
            name,
            "Transition from ${transitionParams.transition.sourceState}, active states: $activeStates"
          )

          val state = activeStates.last() as PayKitMachineStates // return the "deepest" child state
          val customerState = when (state) { // return the "deepest" child state
            PayKitMachineStates.NotStarted -> NotStarted
            PayKitMachineStates.CreatingCustomerRequest -> CreatingCustomerRequest
            // is PayKitMachineStates.ReadyToAuthorize -> ReadyToAuthorize(payKitMachineWrapper.context.customerResponseData!!) // TODO use context?
            is PayKitMachineStates.ReadyToAuthorize -> ReadyToAuthorize(state.data)
            is DeepLinking -> Authorizing
            is Polling -> PollingTransactionStatus
            // DecidedState.Approved -> Approved(payKitMachineWrapper.context.customerResponseData!!)
            is DecidedState.Approved -> Approved(state.data)
            DecidedState.Declined -> Declined
            ExceptionState -> CashAppPayExceptionState((state as ExceptionState).data)
            StartingWithExistingRequest -> RetrievingExistingCustomerRequest
            PayKitMachineStates.UpdatingCustomerRequest -> UpdatingCustomerRequest
          }

          // Or we could do this in the individual state nodes
          analyticsEventDispatcher.genericStateChanged(
            state,
            // payKitMachineWrapper.context.customerResponseData
            (state as? DefaultDataState<*>)?.data as? CustomerResponseData
          )

          // Notify listener of State change.
          callbackListener?.cashAppPayStateDidChange(customerState)
            .orElse {
              Log.e(
                "PayKit",
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
    enforceMachineRunning()
    enforceRegisteredStateUpdatesListener()
    val sandboxBrandID = "BRAND_9t4pg7c16v4lukc98bm9jxyse"
    val stagingBrandID = "BRAND_4wv02dz5v4eg22b3enoffn6rt"

    // Validate [paymentActions] is not empty.
    if (paymentActions.isEmpty()) {
      throw IllegalArgumentException("must provide a payment action")
    }

    payKitMachineWrapper.stateMachine.processEventBlocking(
      CreateCustomerRequest(
        CreatingCustomerRequestData(
          listOf(
            OneTimeAction(
              USD,
              3200,
              sandboxBrandID
            ) as CashAppPayPaymentAction
          ),
          "cashapppay://checkout"
        )
      )
    )
  }

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
    // TODO convert to state machine event
    enforceMachineRunning()

    // Validate [paymentActions] is not empty.
    if (paymentActions.isEmpty()) {
      val exceptionText = "paymentAction should not be empty"
      throw IllegalArgumentException(exceptionText)
    }
    // TODO change to when extension fun
    if (payKitMachineWrapper.stateMachine.activeStates()
        .any { it is PayKitMachineStates.Authorizing || it is PayKitMachineStates.UpdatingCustomerRequest || it is PayKitMachineStates.ReadyToAuthorize }
    ) {
      payKitMachineWrapper.stateMachine.processEventBlocking(
        PayKitEvents.UpdateCustomerRequestEvent.UpdateCustomerRequestAction(
          UpdateCustomerRequestData(paymentActions, requestId)
        )
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

    payKitMachineWrapper.stateMachine.processEventBlocking(
      PayKitEvents.StartWithExistingCustomerRequestEvent.Start(requestId)
    )
    // TODO convert to state machine event
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
    Log.d(
      "CRAIG",
      "stateMachine.payKitMachine.states ${payKitMachineWrapper.stateMachine.activeStates()}"
    )
    enforceMachineRunning()


    if (payKitMachineWrapper.stateMachine.activeStates()
        .none { it is PayKitMachineStates.Authorizing || it is PayKitMachineStates.ReadyToAuthorize }
    ) {
      // TODO we are throwing here... should we throw in other methods?
      logAndSoftCrash(
        "State machine is not ready to authorize",
        CashAppPayIntegrationException("State machine is not ready to authorize"),
      )
      return
    }

    // TODO check data
    payKitMachineWrapper.stateMachine.processEventBlocking(
      PayKitEvents.AuthorizeUsingExistingData
    )
  }

  /**
   * Authorize a customer request with a previously created `customerData`.
   * This function will set this SDK instance internal state to the `customerData` provided here as a function parameter.
   *
   */
  @Throws(IllegalArgumentException::class, RuntimeException::class)
  override fun authorizeCustomerRequest(
    customerData: CustomerResponseData,
  ) {
    enforceRegisteredStateUpdatesListener()

    if (customerData.authFlowTriggers?.mobileUrl.isNullOrEmpty()) {
      throw IllegalArgumentException("customerData is missing redirect url")
    }
    payKitMachineWrapper.stateMachine.processEventBlocking(Authorize(customerData))
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
    logger.logVerbose(CAP_TAG, "Unregistering from state updates")
    callbackListener = null
    payKitLifecycleListener.unregister(this)
    analyticsEventDispatcher.eventListenerRemoved()
    analyticsEventDispatcher.shutdown()

    // Stop any polling operations that might be running.
    singleThreadManager.interruptAllThreads()
  }

  private fun enforceRegisteredStateUpdatesListener() {
    if (callbackListener == null) {
      logAndSoftCrash(
        "No listener registered for state updates.",
        CashAppPayIntegrationException(
          "Shouldn't call this function before registering for state updates via `registerForStateUpdates`.",
        ),
      )
    }
  }

  /**
   * This function will log in production, additionally it will throw an exception in sandbox or debug mode.
   */
  @Throws
  private fun logAndSoftCrash(msg: String, exception: Exception) {
    logger.logError(CAP_TAG, msg, exception)
    // if (useSandboxEnvironment || BuildConfig.DEBUG) {
    //   throw exception
    // }
  }

  /**
   * This function will throw the provided [exception] during development, or change the SDK state to [CashAppPayExceptionState] otherwise.
   */
  @Throws
  private fun softCrashOrStateException(
    msg: String,
    exception: Exception
  ): CashAppPayExceptionState {
    logger.logError(CAP_TAG, msg, exception)
    // if (useSandboxEnvironment || BuildConfig.DEBUG) {
    //   throw exception
    // }
    return CashAppPayExceptionState(exception)
  }

  private fun enforceMachineRunning() {
    if (payKitMachineWrapper.stateMachine.isFinished) {
      val exceptionText = "This SDK instance has already finished. Please start a new one."
      throw IllegalStateException(exceptionText)
    }
  }

  /**
   * Lifecycle callbacks.
   */

  override fun onApplicationForegrounded() {
    logger.logVerbose(CAP_TAG, "onApplicationForegrounded")
    // TODO send message into machine so it starts polling
  }

  override fun onApplicationBackgrounded() {
    logger.logVerbose(CAP_TAG, "onApplicationBackgrounded")
  }
}
