package com.squareup.cash.paykit

import com.squareup.cash.paykit.PayKitState.PayKitException
import com.squareup.cash.paykit.exceptions.PayKitApiNetworkException
import com.squareup.cash.paykit.exceptions.PayKitConnectivityNetworkException
import com.squareup.cash.paykit.impl.CashAppPayKitImpl
import com.squareup.moshi.JsonDataException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit.SECONDS

class NetworkErrorTests {

  @Test
  fun `HTTP code without payload should be wrapped with correct SDK defined exception`() {
    val payKit = CashAppPayKitImpl(FakeData.CLIENT_ID, useSandboxEnvironment = true)
    val mockListener = MockListener()
    payKit.registerForStateUpdates(mockListener)

    // Setup server & mock responses.
    val server = MockWebServer()
    server.enqueue(MockResponse().setResponseCode(503))

    // Start the server.
    server.start()

    val baseUrl = server.url("")
    NetworkManager.baseUrl = baseUrl.toString()
    payKit.createCustomerRequest(FakeData.oneTimePayment)

    // Verify that all the appropriate exception wrapping has occurred for a 503 error.
    assertTrue("Expected PayKitException end state", mockListener.state is PayKitException)
    assertTrue(
      "Expected exception abstraction to be PayKitConnectivityNetworkException",
      (mockListener.state as PayKitException).exception is PayKitConnectivityNetworkException,
    )
    assertTrue(
      "Expected internal exception error state to be IOException",
      ((mockListener.state as PayKitException).exception as PayKitConnectivityNetworkException).e is IOException,
    )
  }

  @Test
  fun `HTTP error code with payload should be wrapped with correct SDK defined exception and contains API deserialized data`() {
    val payKit = CashAppPayKitImpl(FakeData.CLIENT_ID, useSandboxEnvironment = true)
    val mockListener = MockListener()
    payKit.registerForStateUpdates(mockListener)

    // Setup server & mock responses.
    val server = MockWebServer()
    server.enqueue(
      MockResponse().setResponseCode(400).setHeader("content-type", "application/json").setBody(
        """{
  "errors": [
    {
      "category": "INVALID_REQUEST_ERROR",
      "code": "MISSING_REQUIRED_PARAMETER",
      "detail": "One time payments require amount and currency to both be set or unset; one cannot be null while the other is present.",
      "field": "request.action.amount"
    }
  ]
}""",
      ),
    )

    // Start the server.
    server.start()

    val baseUrl = server.url("")
    NetworkManager.baseUrl = baseUrl.toString()
    payKit.createCustomerRequest(FakeData.oneTimePayment)

    // Verify that all the appropriate exception wrapping has occurred for a 400 error.
    assertTrue("Expected PayKitException end state", mockListener.state is PayKitException)
    assertTrue(
      "Expected exception abstraction to be PayKit",
      (mockListener.state as PayKitException).exception is PayKitApiNetworkException,
    )

    // Verify that all the API error details have been deserialized correctly.
    val apiError = (mockListener.state as PayKitException).exception as PayKitApiNetworkException
    assertEquals(apiError.code, "MISSING_REQUIRED_PARAMETER")
    assertEquals(apiError.category, "INVALID_REQUEST_ERROR")
    assertEquals(apiError.field_value, "request.action.amount")
  }

  @Test
  fun `network request timeout should be wrapped with correct SDK defined exception`() {
    val payKit = CashAppPayKitImpl(FakeData.CLIENT_ID, useSandboxEnvironment = true)
    val mockListener = MockListener()
    payKit.registerForStateUpdates(mockListener)
    // Setup server & mock responses.
    val server = MockWebServer()
    server.enqueue(
      MockResponse().setResponseCode(200).setBodyDelay(10, SECONDS).setHeadersDelay(10, SECONDS),
    )

    // Start the server.
    server.start()
    val baseUrl = server.url("")
    NetworkManager.baseUrl = baseUrl.toString()
    NetworkManager.DEFAULT_NETWORK_TIMEOUT_MILLISECONDS = 1

    payKit.createCustomerRequest(FakeData.oneTimePayment)

    // Verify that a timeout error was captured and relayed to the SDK listener.
    assertTrue(
      "Expected SocketTimeoutException",
      ((mockListener.state as PayKitException).exception as PayKitConnectivityNetworkException).e is SocketTimeoutException,
    )
  }

  @Test
  fun `unsupported json payload should be wrapped in corresponding SDK exception`() {
    val payKit = CashAppPayKitImpl(FakeData.CLIENT_ID, useSandboxEnvironment = true)
    val mockListener = MockListener()
    payKit.registerForStateUpdates(mockListener)

    // Setup server & mock responses.
    val server = MockWebServer()

    // Below response is an invalid JSON, because `id` should NOT be `null`.
    server.enqueue(
      MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(
        """{
  "request": {
    "id": null,
    "status": "PENDING",
    "actions": [
      {
        "type": "ONE_TIME_PAYMENT",
        "amount": 500,
        "currency": "USD",
        "scope_id": "BRAND_9kx6p0mkuo97jnl025q9ni94t"
      }
    ],
    "origin": {
      "type": "DIRECT"
    },
    "auth_flow_triggers": {
      "qr_code_image_url": "https://sandbox.api.cash.app/qr/sandbox/v1/GRR_k1xx5sp48azs73kqgkpdqpff-rxb309?rounded=0&format=png",
      "qr_code_svg_url": "https://sandbox.api.cash.app/qr/sandbox/v1/GRR_k1xx5sp48azs73kqgkpdqpff-rxb309?rounded=0&format=svg",
      "mobile_url": "https://sandbox.api.cash.app/customer-request/v1/requests/GRR_k1xx5sp48azs73kqgkpdqpff/interstitial?validity_token=rxb309",
      "refreshes_at": "2022-11-11T19:28:25.451Z"
    },
    "created_at": "2022-11-11T19:27:55.475Z",
    "updated_at": "2022-11-11T19:27:55.475Z",
    "expires_at": "2022-11-11T20:27:55.451Z",
    "requester_profile": {
      "name": "SDK Hacking: The Brand",
      "logo_url": "defaultlogo.jpg"
    },
    "channel": "IN_APP"
  }
}""",
      ),
    )

    // Start the server.
    server.start()

    val baseUrl = server.url("")
    NetworkManager.baseUrl = baseUrl.toString()
    payKit.createCustomerRequest(FakeData.oneTimePayment)

    // Verify that we got the appropriate JSON deserialization error.
    assertTrue("Expected PayKitException end state", mockListener.state is PayKitException)
    assertTrue(
      "Expected JsonDataException",
      (mockListener.state as PayKitException).exception is JsonDataException,
    )
  }

  /**
   * Our own Mock [CashAppPayKitListener] listener, that allows us to wait on a new state before continuing test execution.
   */
  internal class MockListener : CashAppPayKitListener {
    var state: PayKitState? = null

    override fun payKitStateDidChange(newState: PayKitState) {
      state = newState
    }
  }
}
