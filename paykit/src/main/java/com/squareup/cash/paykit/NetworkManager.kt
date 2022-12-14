package com.squareup.cash.paykit

import com.squareup.cash.paykit.RequestType.GET
import com.squareup.cash.paykit.RequestType.POST
import com.squareup.cash.paykit.exceptions.PayKitApiNetworkException
import com.squareup.cash.paykit.exceptions.PayKitConnectivityNetworkException
import com.squareup.cash.paykit.models.common.Action
import com.squareup.cash.paykit.models.common.NetworkResult
import com.squareup.cash.paykit.models.common.NetworkResult.Failure
import com.squareup.cash.paykit.models.common.NetworkResult.Success
import com.squareup.cash.paykit.models.request.CreateCustomerRequest
import com.squareup.cash.paykit.models.request.CustomerRequestData
import com.squareup.cash.paykit.models.response.ApiErrorResponse
import com.squareup.cash.paykit.models.response.CustomerTopLevelResponse
import com.squareup.cash.paykit.models.sdk.PayKitPaymentAction
import com.squareup.cash.paykit.models.sdk.PayKitPaymentAction.OnFileAction
import com.squareup.cash.paykit.models.sdk.PayKitPaymentAction.OneTimeAction
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

private const val BASE_URL_SANDBOX = "https://sandbox.api.cash.app/customer-request/v1/"
private const val BASE_URL_RELEASE = "https://api.cash.app/customer-request/v1/"
private const val BASE_URL = BASE_URL_SANDBOX
private const val CREATE_CUSTOMER_REQUEST_ENDPOINT = "${BASE_URL}requests"
private const val RETRIEVE_EXISTING_REQUEST_ENDPOINT = "${BASE_URL}requests/"

enum class RequestType {
  GET,
  POST,
  PATCH
}

internal object NetworkManager {

  private const val CHANNEL_IN_APP = "IN_APP"
  private const val PAYMENT_TYPE_ONE_TIME = "ONE_TIME_PAYMENT"
  private const val PAYMENT_TYPE_ON_FILE = "ON_FILE_PAYMENT"

  private const val DEFAULT_NETWORK_TIMEOUT_SECONDS = 60

  @Throws(IOException::class)
  fun createCustomerRequest(
    clientId: String,
    paymentAction: PayKitPaymentAction
  ): NetworkResult<CustomerTopLevelResponse> {
    return when (paymentAction) {
      is OnFileAction -> onFilePaymentCustomerRequest(clientId, paymentAction)
      is OneTimeAction -> oneTimePaymentCustomerRequest(clientId, paymentAction)
    }
  }

  fun retrieveUpdatedRequestData(
    clientId: String,
    requestId: String
  ): NetworkResult<CustomerTopLevelResponse> {
    return executeNetworkRequest(
      GET,
      RETRIEVE_EXISTING_REQUEST_ENDPOINT + requestId,
      clientId,
      null
    )
  }

  private fun onFilePaymentCustomerRequest(
    clientId: String,
    paymentAction: OnFileAction
  ): NetworkResult<CustomerTopLevelResponse> {
    // Create request data.
    val scopeIdOrClientId = paymentAction.scopeId ?: clientId
    val requestAction =
      Action(
        scopeId = scopeIdOrClientId,
        type = PAYMENT_TYPE_ON_FILE
      )
    val requestData = CustomerRequestData(
      actions = listOf(requestAction),
      channel = CHANNEL_IN_APP,
      redirectUri = paymentAction.redirectUri
    )
    val createCustomerRequest = CreateCustomerRequest(
      idempotencyKey = UUID.randomUUID().toString(),
      customerRequestData = requestData
    )

    return executeNetworkRequest(
      POST,
      CREATE_CUSTOMER_REQUEST_ENDPOINT,
      clientId,
      createCustomerRequest
    )
  }

  private fun oneTimePaymentCustomerRequest(
    clientId: String,
    paymentAction: OneTimeAction
  ): NetworkResult<CustomerTopLevelResponse> {
    // Create request data.
    val scopeIdOrClientId = paymentAction.scopeId ?: clientId
    val requestAction =
      Action(
        amount_cents = paymentAction.amount,
        currency = paymentAction.currency?.backendValue,
        scopeId = scopeIdOrClientId,
        type = PAYMENT_TYPE_ONE_TIME
      )
    val requestData = CustomerRequestData(
      actions = listOf(requestAction),
      channel = CHANNEL_IN_APP,
      redirectUri = paymentAction.redirectUri
    )
    val createCustomerRequest = CreateCustomerRequest(
      idempotencyKey = UUID.randomUUID().toString(),
      customerRequestData = requestData
    )

    return executeNetworkRequest(
      POST,
      CREATE_CUSTOMER_REQUEST_ENDPOINT,
      clientId,
      createCustomerRequest
    )
  }

  @OptIn(ExperimentalStdlibApi::class)
  /**
   * POST Request.
   * @param In Class for serializing the request
   * @param Out Class for deserializing the response
   * @param clientId Client ID for authenticating the request
   * @param requestPayload Request payload, an instance of the `In` class.
   */
  private inline fun <reified In : Any, reified Out : Any> executeNetworkRequest(
    requestType: RequestType,
    endpointUrl: String,
    clientId: String,
    requestPayload: In?
  ): NetworkResult<Out> {
    val url = URL(endpointUrl)
    val urlConnection: HttpURLConnection = url.openConnection() as HttpURLConnection
    urlConnection.requestMethod = requestType.name
    urlConnection.connectTimeout = DEFAULT_NETWORK_TIMEOUT_SECONDS * 1000
    urlConnection.readTimeout = DEFAULT_NETWORK_TIMEOUT_SECONDS * 1000
    urlConnection.setRequestProperty("Content-Type", "application/json")
    urlConnection.setRequestProperty("Accept", "application/json")
    urlConnection.setRequestProperty("Authorization", "Client $clientId")

    if (requestType == POST) {
      urlConnection.doOutput = true
      urlConnection.setChunkedStreamingMode(0)
    }

    try {
      val moshi: Moshi = Moshi.Builder().build()

      if (requestPayload != null) {
        val outStream: OutputStream = BufferedOutputStream(urlConnection.outputStream)
        val writer = BufferedWriter(
          OutputStreamWriter(
            outStream, "UTF-8"
          )
        )

        val requestJsonAdapter: JsonAdapter<In> = moshi.adapter()
        val jsonData: String = requestJsonAdapter.toJson(requestPayload)
        writer.write(jsonData)
        writer.flush()
      }

      val code = urlConnection.responseCode
      if (code != HttpURLConnection.HTTP_CREATED && code != HttpURLConnection.HTTP_OK) {
        // Handle 5XX errors.
        if (code >= 500) {
          return NetworkResult.failure(PayKitConnectivityNetworkException(IOException("Got server code $code")))
        }

        // Handle 3XX & 4XX errors.
        val apiErrorResponse: NetworkResult<ApiErrorResponse> =
          deserializeResponse(urlConnection, moshi)
        return when (apiErrorResponse) {
          is Failure -> NetworkResult.failure(PayKitConnectivityNetworkException(apiErrorResponse.exception))
          is Success -> {
            val apiError = apiErrorResponse.data.apiErrors.first()
            val apiException = PayKitApiNetworkException(
              apiError.category,
              apiError.code,
              apiError.detail,
              apiError.field_value
            )
            NetworkResult.failure(apiException)
          }
        }
      }

      return deserializeResponse(urlConnection, moshi)
    } catch (e: SocketTimeoutException) {
      return NetworkResult.failure(PayKitConnectivityNetworkException(e))
    } finally {
      urlConnection.disconnect()
    }
  }

  @OptIn(ExperimentalStdlibApi::class)
  private inline fun <reified Out : Any> deserializeResponse(
    urlConnection: HttpURLConnection,
    moshi: Moshi
  ): NetworkResult<Out> {
    // TODO: Could probably leverage OKIO to improve this code. ( https://www.notion.so/cashappcash/Would-okio-benefit-the-low-level-network-handling-b8f55044c1e249a995f544f1f9de3c4a )
    try {
      // In the case HTTP status is an error, the output will belong to `errorStream`.
      val streamToUse = try {
        urlConnection.inputStream
      } catch (e: FileNotFoundException) {
        urlConnection.errorStream
      }

      streamToUse.use { inputStream ->
        inputStream.bufferedReader().use { buffered ->
          val responseLines = buffered.readLines()
          val sb = StringBuilder()
          responseLines.forEach { sb.append(it) }
          val responseJson = sb.toString()

          val jsonAdapterResponse: JsonAdapter<Out> = moshi.adapter()

          val responseModel = jsonAdapterResponse.fromJson(responseJson)
          if (responseModel != null) {
            return NetworkResult.success(responseModel)
          }
          return NetworkResult.failure(IOException("Failed to deserialize response data"))
        }
      }
    } catch (e: SocketTimeoutException) {
      return NetworkResult.failure(PayKitConnectivityNetworkException(e))
    }
  }
}
