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

import app.cash.paykit.core.NetworkManager
import app.cash.paykit.core.analytics.PayKitAnalyticsEventDispatcher
import app.cash.paykit.core.exceptions.CashAppPayApiNetworkException
import app.cash.paykit.core.exceptions.CashAppPayConnectivityNetworkException
import app.cash.paykit.core.impl.RequestType.GET
import app.cash.paykit.core.impl.RequestType.PATCH
import app.cash.paykit.core.impl.RequestType.POST
import app.cash.paykit.core.models.analytics.EventStream2Response
import app.cash.paykit.core.models.common.NetworkResult
import app.cash.paykit.core.models.common.NetworkResult.Failure
import app.cash.paykit.core.models.common.NetworkResult.Success
import app.cash.paykit.core.models.request.CreateCustomerRequest
import app.cash.paykit.core.models.request.CustomerRequestDataFactory
import app.cash.paykit.core.models.response.ApiErrorResponse
import app.cash.paykit.core.models.response.CustomerTopLevelResponse
import app.cash.paykit.core.models.sdk.CashAppPayPaymentAction
import app.cash.paykit.core.network.MoshiProvider
import app.cash.paykit.core.network.RetryManager
import app.cash.paykit.core.network.RetryManagerImpl
import app.cash.paykit.core.network.RetryManagerOptions
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.*

enum class RequestType {
  GET,
  POST,
  PATCH,
}

internal class NetworkManagerImpl(
  private val baseUrl: String,
  private val analyticsBaseUrl: String,
  private val userAgentValue: String,
  private val okHttpClient: OkHttpClient,
  private val retryManagerOptions: RetryManagerOptions = RetryManagerOptions(),
) : NetworkManager {

  val CREATE_CUSTOMER_REQUEST_ENDPOINT: String
    get() = "${baseUrl}requests"

  val RETRIEVE_EXISTING_REQUEST_ENDPOINT: String
    get() = "${baseUrl}requests/"

  val UPDATE_CUSTOMER_REQUEST_ENDPOINT: String
    get() = "${baseUrl}requests/"

  val ANALYTICS_ENDPOINT: String
    get() = "${analyticsBaseUrl}2.0/log/eventstream"

  var analyticsEventDispatcher: PayKitAnalyticsEventDispatcher? = null


  // why does this throw?? shouldn't we just error?
  @Throws(IOException::class)
  override fun createCustomerRequest(
    clientId: String,
    paymentActions: List<CashAppPayPaymentAction>,
    redirectUri: String?,
  ): NetworkResult<CustomerTopLevelResponse> {
    val customerRequestData = CustomerRequestDataFactory.build(clientId, redirectUri, paymentActions)
    val createCustomerRequest = CreateCustomerRequest(
      idempotencyKey = UUID.randomUUID().toString(),
      customerRequestData = customerRequestData,
    )

    // Record analytics.
    analyticsEventDispatcher?.createdCustomerRequest(paymentActions, customerRequestData.actions, redirectUri)

    // TODO wrap this in a try catch? so we can return NetworkResult.failure?
    return executeNetworkRequest(
      POST,
      CREATE_CUSTOMER_REQUEST_ENDPOINT,
      clientId,
      createCustomerRequest,
    )
  }

  @Throws(IOException::class)
  override fun updateCustomerRequest(
    clientId: String,
    requestId: String,
    paymentActions: List<CashAppPayPaymentAction>,
  ): NetworkResult<CustomerTopLevelResponse> {
    val customerRequestData =
      CustomerRequestDataFactory.build(clientId, null, paymentActions, isRequestUpdate = true)
    val createCustomerRequest = CreateCustomerRequest(
      customerRequestData = customerRequestData,
    )

    // Record analytics.
    analyticsEventDispatcher?.updatedCustomerRequest(requestId, paymentActions, customerRequestData.actions)

    return executeNetworkRequest(
      PATCH,
      UPDATE_CUSTOMER_REQUEST_ENDPOINT + requestId,
      clientId,
      createCustomerRequest,
    )
  }

  override fun retrieveUpdatedRequestData(
    clientId: String,
    requestId: String,
  ): NetworkResult<CustomerTopLevelResponse> {
    return executeNetworkRequest(
      GET,
      RETRIEVE_EXISTING_REQUEST_ENDPOINT + requestId,
      clientId,
      null,
    )
  }

  override fun uploadAnalyticsEvents(eventsAsJson: List<String>): NetworkResult<EventStream2Response> {
    val analyticsRequest = "{\"events\": [${eventsAsJson.joinToString()}]}"
    return executePlainNetworkRequest(
      POST,
      ANALYTICS_ENDPOINT,
      null,
      RetryManagerImpl(retryManagerOptions),
      analyticsRequest,
    )
  }

  @OptIn(ExperimentalStdlibApi::class)
  /**
   * Execute the actual network request, and return a result wrapped in a [NetworkResult].
   *
   * @param In Class for serializing the request
   * @param Out Class for deserializing the response
   * @param clientId Client ID for authenticating the request
   * @param requestPayload Request payload, an instance of the `In` class.
   */
  private inline fun <reified In : Any, reified Out : Any> executeNetworkRequest(
    requestType: RequestType,
    endpointUrl: String,
    clientId: String,
    requestPayload: In?,
  ): NetworkResult<Out> {
    val moshi: Moshi = MoshiProvider.provideDefault()
    val requestJsonAdapter: JsonAdapter<In> = moshi.adapter()
    val jsonData: String = requestJsonAdapter.toJson(requestPayload)
    return executePlainNetworkRequest(
      requestType,
      endpointUrl,
      clientId,
      RetryManagerImpl(retryManagerOptions),
      jsonData,
    )
  }

  /**
   * Similar to [executeNetworkRequest], but receives a pre-built string for the request body.
   * Execute the actual network request, and return a result wrapped in a [NetworkResult].
   *
   * @param Out Class for deserializing the response
   * @param clientId Client ID for authenticating the request
   * @param requestJsonPayload String representing the body of the request
   */
  private inline fun <reified Out : Any> executePlainNetworkRequest(
    requestType: RequestType,
    endpointUrl: String,
    clientId: String?,
    retryManager: RetryManager,
    requestJsonPayload: String,
  ): NetworkResult<Out> {
    val requestBuilder = Request.Builder()
      .url(endpointUrl)
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/json")
      .addHeader("Accept-Language", Locale.getDefault().toLanguageTag())
      .addHeader("User-Agent", userAgentValue)

    if (clientId != null) {
      requestBuilder.addHeader("Authorization", "Client $clientId")
    }

    val moshi: Moshi = MoshiProvider.provideDefault()

    with(requestBuilder) {
      when (requestType) {
        GET -> get()
        POST -> post(requestJsonPayload.toRequestBody(JSON_MEDIA_TYPE))
        PATCH -> patch(requestJsonPayload.toRequestBody(JSON_MEDIA_TYPE))
      }
    }

    var retryException: Exception = IOException("Network retries failed!")
    while (retryManager.shouldRetry()) {
      try {
        // Add number of HTTP retries to header, so we can easily track this in the future if we have too.
        if (retryManager.getRetryCount() > 0) {
          requestBuilder.removeHeader("paykit-retries-count")
          requestBuilder.addHeader("paykit-retries-count", retryManager.getRetryCount().toString())
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
          if (response.code >= HttpURLConnection.HTTP_INTERNAL_ERROR) {
            retryManager.networkAttemptFailed()

            // Wait until the next retry.
            if (retryManager.shouldRetry()) {
              try {
                Thread.sleep(retryManager.timeUntilNextRetry().inWholeMilliseconds)
              } catch (e: InterruptedException) {
                return NetworkResult.failure(CashAppPayConnectivityNetworkException(retryException))
              }
            }
            return@use
          }

          if (!response.isSuccessful) {
            // Unsuccessfully response is handled here.
            // Under normal circumstances:
            //  - 3xx errors won’t have a payload.
            //  - 4xx are guaranteed to have a payload.
            //  - 5xx should have a payload, but there might be situations where they do not.
            //
            // So as a result our logic here is : use the payload if it exists, otherwise simply propagate the error code.
            val apiErrorResponse: NetworkResult<ApiErrorResponse> =
              deserializeResponse(response.body?.string() ?: "", moshi)
            return when (apiErrorResponse) {
              is Failure -> NetworkResult.failure(
                CashAppPayConnectivityNetworkException(apiErrorResponse.exception),
              )

              is Success -> {
                val apiError = apiErrorResponse.data.apiErrors.first()
                val apiException = CashAppPayApiNetworkException(
                  apiError.category,
                  apiError.code,
                  apiError.detail,
                  apiError.field_value,
                )
                NetworkResult.failure(apiException)
              }
            }
          }

          // Success continues here.
          return deserializeResponse(response.body!!.string(), moshi)
        }
      } catch (e: Exception) {
        retryManager.networkAttemptFailed()

        // Wait until the next retry.
        if (retryManager.shouldRetry()) {
          try {
            Thread.sleep(retryManager.timeUntilNextRetry().inWholeMilliseconds)
          } catch (e: InterruptedException) {
            return NetworkResult.failure(CashAppPayConnectivityNetworkException(retryException))
          }
        }
        retryException = e
      }
    }
    return NetworkResult.failure(CashAppPayConnectivityNetworkException(retryException))
  }

  @OptIn(ExperimentalStdlibApi::class)
  private inline fun <reified Out : Any> deserializeResponse(
    responseString: String,
    moshi: Moshi,
  ): NetworkResult<Out> {
    try {
      val jsonAdapterResponse: JsonAdapter<Out> = moshi.adapter()

      val responseModel = jsonAdapterResponse.fromJson(responseString)
      if (responseModel != null) {
        return NetworkResult.success(responseModel)
      }
      return NetworkResult.failure(IOException("Failed to deserialize response data."))
    } catch (e: SocketTimeoutException) {
      return NetworkResult.failure(CashAppPayConnectivityNetworkException(e))
    } catch (e: JsonEncodingException) {
      return NetworkResult.failure(e)
    } catch (e: Exception) {
      return NetworkResult.failure(e)
    }
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}
