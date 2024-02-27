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
package app.cash.paykit.core

import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import app.cash.paykit.analytics.AnalyticsOptions
import app.cash.paykit.analytics.PayKitAnalytics
import app.cash.paykit.core.analytics.PayKitAnalyticsEventDispatcher
import app.cash.paykit.core.analytics.PayKitAnalyticsEventDispatcherImpl
import app.cash.paykit.core.android.ApplicationContextHolder
import app.cash.paykit.core.exceptions.CashAppPayIntegrationException
import app.cash.paykit.core.impl.CashAppPayImpl
import app.cash.paykit.core.impl.CashAppPayLifecycleObserverImpl
import app.cash.paykit.core.impl.NetworkManagerImpl
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction
import app.cash.paykit.core.network.OkHttpProvider
import app.cash.paykit.core.utils.UserAgentProvider
import app.cash.paykit.logging.CashAppLogger
import app.cash.paykit.logging.CashAppLoggerImpl
import kotlin.time.Duration.Companion.seconds

interface CashAppPay {

  /**
   * Create customer request given a [CashAppPayPaymentAction].
   *
   * Must be called from a background thread.
   *
   * @param paymentAction A wrapper class that contains all of the necessary ingredients for building a customer requests.
   *                      Look at [PayKitPaymentAction] for more details.
   * @param redirectUri The URI for Cash App to redirect back to your app. If you do not set this, back navigation from CashApp might not work as intended.
   */
  @WorkerThread
  fun createCustomerRequest(paymentAction: CashAppPayPaymentAction, redirectUri: String?)

  /**
   * Create customer request given list of [CashAppPayPaymentAction].
   *
   * Must be called from a background thread.
   *
   * @param paymentActions A wrapper class that contains all of the necessary ingredients for building one or more customer requests.
   *                      Look at [PayKitPaymentAction] for more details.
   * @param redirectUri The URI for Cash App to redirect back to your app. If you do not set this, back navigation from CashApp might not work as intended.
   */
  @WorkerThread
  fun createCustomerRequest(paymentActions: List<CashAppPayPaymentAction>, redirectUri: String?)

  /**
   * Update an existing customer request given its [requestId] and the updated definitions contained within [CashAppPayPaymentAction].
   *
   * Must be called from a background thread.
   *
   * @param requestId ID of the request we intent do update.
   * @param paymentAction A wrapper class that contains all of the necessary ingredients for updating a customer request for a given [requestId].
   *                      Look at [CashAppPayPaymentAction] for more details.
   */
  @WorkerThread
  fun updateCustomerRequest(
    requestId: String,
    paymentAction: CashAppPayPaymentAction,
  )

  /**
   * Update an existing customer request given its [requestId] and the updated definitions contained within a list of [CashAppPayPaymentAction].
   *
   * Must be called from a background thread.
   *
   * @param requestId ID of the request we intent do update.
   * @param paymentActions A wrapper class that contains all of the necessary ingredients for updating one more more customer requests that share the same [requestId].
   *                      Look at [CashAppPayPaymentAction] for more details.
   */
  @WorkerThread
  fun updateCustomerRequest(
    requestId: String,
    paymentActions: List<CashAppPayPaymentAction>,
  )

  /**
   * Retrieve an existing customer request, provided its [requestId]. This should be used as a
   * starting point, for cases where you want to recover the state of an existing or in-flight customer
   * request.
   *
   * Must be called from a background thread.
   *
   * @param requestId ID of the request we intent do retrieve.
   */
  @WorkerThread
  // TODO should we throw here as well?
  fun startWithExistingCustomerRequest(
    requestId: String,
  )

  /**
   * Authorize a customer request. This function must be called AFTER `createCustomerRequest`.
   * Not doing so will result in an Exception in sandbox mode, and a silent error log in production.
   */
  @Throws(IllegalArgumentException::class, CashAppPayIntegrationException::class)
  fun authorizeCustomerRequest()

  /**
   * Authorize a customer request with a previously created `customerData`.
   * This function will set this SDK instance internal state to the `customerData` provided here as a function parameter.
   *
   */
  @Throws(IllegalArgumentException::class, RuntimeException::class)
  fun authorizeCustomerRequest(
    customerData: CustomerResponseData,
  )

  /**
   *  Register a [CashAppPayListener] to receive PayKit callbacks.
   */
  fun registerForStateUpdates(listener: CashAppPayListener)

  /**
   *  Unregister any previously registered [CashAppPayListener] from PayKit updates.
   */
  fun unregisterFromStateUpdates()
}

object CashAppPayFactory {

  private val cashAppPayLifecycleObserver: CashAppPayLifecycleObserver = CashAppPayLifecycleObserverImpl()

  private fun buildPayKitAnalytics(isSandbox: Boolean, cashAppLogger: CashAppLogger) =
    with(ApplicationContextHolder.applicationContext) {
      val info = packageManager.getPackageInfo(packageName, 0)

      @Suppress("DEPRECATION")
      val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info?.longVersionCode
      } else {
        info?.versionCode
      }

      val dbName = if (isSandbox) {
        ANALYTICS_DB_NAME_SANDBOX
      } else {
        ANALYTICS_DB_NAME_PROD
      }

      PayKitAnalytics(
        context = ApplicationContextHolder.applicationContext,
        options = AnalyticsOptions(
          delay = 10.seconds,
          logLevel = Log.WARN,
          databaseName = dbName,
          isLoggerDisabled = !BuildConfig.DEBUG,
          applicationVersionCode = versionCode!!.toInt(), // casting as int gives us the "legacy" version code
        ),
        cashAppLogger = cashAppLogger,
      )
    }

  private fun getUserAgentValue(): String {
    return UserAgentProvider.provideUserAgent(ApplicationContextHolder.applicationContext)
  }

  /**
   * @param clientId Client Identifier that should be provided by Cash PayKit integration.
   */
  fun create(
    clientId: String,
  ): CashAppPay {
    val networkManager = NetworkManagerImpl(
      BASE_URL_PRODUCTION,
      ANALYTICS_BASE_URL,
      userAgentValue = getUserAgentValue(),
      okHttpClient = defaultOkHttpClient,
    )
    val analytics = buildPayKitAnalytics(isSandbox = false, cashAppPayLogger)
    val analyticsEventDispatcher =
      buildPayKitAnalyticsEventDispatcher(clientId, networkManager, analytics, ANALYTICS_PROD_ENVIRONMENT)
    networkManager.analyticsEventDispatcher = analyticsEventDispatcher

    return CashAppPayImpl(
      clientId = clientId,
      networkManager = networkManager,
      analyticsEventDispatcher = analyticsEventDispatcher,
      payKitLifecycleListener = cashAppPayLifecycleObserver,
      useSandboxEnvironment = false,
//      logger = cashAppPayLogger,
    )
  }

  /**
   * @param clientId Client Identifier that should be provided by Cash PayKit integration.
   */
  fun createSandbox(
    clientId: String,
  ): CashAppPay {
    val networkManager = NetworkManagerImpl(
      BASE_URL_SANDBOX,
      ANALYTICS_BASE_URL,
      userAgentValue = getUserAgentValue(),
      okHttpClient = defaultOkHttpClient,
    )

    val analytics = buildPayKitAnalytics(isSandbox = true, cashAppPayLogger)
    val analyticsEventDispatcher =
      buildPayKitAnalyticsEventDispatcher(clientId, networkManager, analytics, ANALYTICS_SANDBOX_ENVIRONMENT)
    networkManager.analyticsEventDispatcher = analyticsEventDispatcher

    return CashAppPayImpl(
      clientId = clientId,
      networkManager = networkManager,
      analyticsEventDispatcher = analyticsEventDispatcher,
      payKitLifecycleListener = cashAppPayLifecycleObserver,
      useSandboxEnvironment = true,
//      logger = cashAppPayLogger,
    )
  }

  private fun buildPayKitAnalyticsEventDispatcher(
    clientId: String,
    networkManager: NetworkManager,
    eventsManager: PayKitAnalytics,
    environment: String,
  ): PayKitAnalyticsEventDispatcher {
    val sdkVersion =
      ApplicationContextHolder.applicationContext.getString(R.string.cap_version)
    return PayKitAnalyticsEventDispatcherImpl(
      sdkVersion,
      clientId,
      getUserAgentValue(),
      environment,
      eventsManager,
      networkManager,
    )
  }

  private val defaultOkHttpClient = OkHttpProvider.provideOkHttpClient()

  private val cashAppPayLogger: CashAppLogger = CashAppLoggerImpl()

  // Do NOT add `const` to these, as it will invalidate reflection for our Dev App.
  private val BASE_URL_SANDBOX = "https://sandbox.api.cash.app/customer-request/v1/"
  private val BASE_URL_PRODUCTION = "https://api.cash.app/customer-request/v1/"
  private val ANALYTICS_BASE_URL = "https://api.squareup.com/"
  private val ANALYTICS_DB_NAME_PROD = "paykit-events.db"
  private val ANALYTICS_DB_NAME_SANDBOX = "paykit-events-sandbox.db"
  private val ANALYTICS_PROD_ENVIRONMENT = "production"
  private val ANALYTICS_SANDBOX_ENVIRONMENT = "sandbox"

  // This is the threshold for when in advance of a token expiring we should refresh it.
  internal val TOKEN_REFRESH_WINDOW = 10.seconds
}

interface CashAppPayListener {
  fun cashAppPayStateDidChange(newState: CashAppPayState)
}
