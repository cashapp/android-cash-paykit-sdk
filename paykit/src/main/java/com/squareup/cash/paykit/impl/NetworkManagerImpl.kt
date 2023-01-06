package com.squareup.cash.paykit.impl

import com.squareup.cash.paykit.exceptions.PayKitApiNetworkException
import com.squareup.cash.paykit.exceptions.PayKitConnectivityNetworkException
import com.squareup.cash.paykit.impl.RequestType.GET
import com.squareup.cash.paykit.impl.RequestType.PATCH
import com.squareup.cash.paykit.impl.RequestType.POST
import com.squareup.cash.paykit.models.common.NetworkResult
import com.squareup.cash.paykit.models.common.NetworkResult.Failure
import com.squareup.cash.paykit.models.common.NetworkResult.Success
import com.squareup.cash.paykit.models.request.CreateCustomerRequest
import com.squareup.cash.paykit.models.request.CustomerRequestDataFactory
import com.squareup.cash.paykit.models.response.ApiErrorResponse
import com.squareup.cash.paykit.models.response.CustomerTopLevelResponse
import com.squareup.cash.paykit.models.sdk.PayKitPaymentAction
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

enum class RequestType {
  GET,
  POST,
  PATCH,
}

internal class NetworkManagerImpl(
  private val baseUrl: String,
  private val networkTimeoutMilliseconds: Int = DEFAULT_NETWORK_TIMEOUT_MILLISECONDS,
) : NetworkManager {

  val CREATE_CUSTOMER_REQUEST_ENDPOINT: String
    get() = "${baseUrl}requests"

  val RETRIEVE_EXISTING_REQUEST_ENDPOINT: String
    get() = "${baseUrl}requests/"

  val UPDATE_CUSTOMER_REQUEST_ENDPOINT: String
    get() = "${baseUrl}requests/"

  @Throws(IOException::class)
  override fun createCustomerRequest(
    clientId: String,
    paymentAction: PayKitPaymentAction,
  ): NetworkResult<CustomerTopLevelResponse> {
    val customerRequestData = CustomerRequestDataFactory.build(clientId, paymentAction)
    val createCustomerRequest = CreateCustomerRequest(
      idempotencyKey = UUID.randomUUID().toString(),
      customerRequestData = customerRequestData,
    )

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
    paymentAction: PayKitPaymentAction,
  ): NetworkResult<CustomerTopLevelResponse> {
    val customerRequestData =
      CustomerRequestDataFactory.build(clientId, paymentAction, isRequestUpdate = true)
    val createCustomerRequest = CreateCustomerRequest(
      customerRequestData = customerRequestData,
    )
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
    requestPayload: In?,
  ): NetworkResult<Out> {
    val url = URL(endpointUrl)
    val urlConnection: HttpURLConnection = url.openConnection() as HttpURLConnection
    urlConnection.requestMethod = requestType.name
    urlConnection.connectTimeout = networkTimeoutMilliseconds
    urlConnection.readTimeout = networkTimeoutMilliseconds
    urlConnection.setRequestProperty("Content-Type", "application/json")
    urlConnection.setRequestProperty("Accept", "application/json")
    urlConnection.setRequestProperty("Authorization", "Client $clientId")

    if (requestType == POST || requestType == PATCH) {
      urlConnection.doOutput = true
      urlConnection.setChunkedStreamingMode(0)
    }

    try {
      val moshi: Moshi = Moshi.Builder().build()

      if (requestPayload != null) {
        val outStream: OutputStream = BufferedOutputStream(urlConnection.outputStream)
        val writer = BufferedWriter(
          OutputStreamWriter(
            outStream,
            "UTF-8",
          ),
        )

        val requestJsonAdapter: JsonAdapter<In> = moshi.adapter()
        val jsonData: String = requestJsonAdapter.toJson(requestPayload)
        writer.write(jsonData)
        writer.flush()
      }

      val responseCode = urlConnection.responseCode
      if (responseCode != HttpURLConnection.HTTP_CREATED && responseCode != HttpURLConnection.HTTP_OK) {
        // Under normal circumstances:
        //  - 3xx errors won’t have a payload.
        //  - 4xx are guaranteed to have a payload.
        //  - 5xx should have a payload, but there might be situations where they do not.
        //
        // So as a result our logic here is : use the payload if it exists, otherwise simply propagate the error code.
        val apiErrorResponse: NetworkResult<ApiErrorResponse> =
          deserializeResponse(urlConnection, moshi)
        return when (apiErrorResponse) {
          is Failure -> NetworkResult.failure(
            PayKitConnectivityNetworkException(apiErrorResponse.exception),
          )
          is Success -> {
            val apiError = apiErrorResponse.data.apiErrors.first()
            val apiException = PayKitApiNetworkException(
              apiError.category,
              apiError.code,
              apiError.detail,
              apiError.field_value,
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
    moshi: Moshi,
  ): NetworkResult<Out> {
    // TODO: Could probably leverage OKIO to improve this code. ( https://www.notion.so/cashappcash/Would-okio-benefit-the-low-level-network-handling-b8f55044c1e249a995f544f1f9de3c4a )
    try {
      val streamToUse = try {
        urlConnection.inputStream
      } catch (e: Exception) {
        // In the case HTTP status is an error, the output will belong to `errorStream`.
        if (urlConnection.errorStream == null) {
          // If both inputStream and errorStream are missing, there is no response payload. Therefore return an exception with the appropriate HTTP code.
          return NetworkResult.failure(IOException("Got server code ${urlConnection.responseCode}"))
        }
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
          return NetworkResult.failure(IOException("Failed to deserialize response data."))
        }
      }
    } catch (e: SocketTimeoutException) {
      return NetworkResult.failure(PayKitConnectivityNetworkException(e))
    } catch (e: JsonEncodingException) {
      return NetworkResult.failure(e)
    } catch (e: Exception) {
      return NetworkResult.failure(e)
    }
  }

  companion object {
    const val DEFAULT_NETWORK_TIMEOUT_MILLISECONDS = 60_000
  }
}
