package app.cash.paykit.core

import android.content.Context
import app.cash.paykit.core.PayKitState.Approved
import app.cash.paykit.core.PayKitState.Authorizing
import app.cash.paykit.core.PayKitState.CreatingCustomerRequest
import app.cash.paykit.core.PayKitState.Declined
import app.cash.paykit.core.PayKitState.NotStarted
import app.cash.paykit.core.PayKitState.PollingTransactionStatus
import app.cash.paykit.core.PayKitState.ReadyToAuthorize
import app.cash.paykit.core.PayKitState.UpdatingCustomerRequest
import app.cash.paykit.core.impl.CashAppPayKitImpl
import app.cash.paykit.core.impl.PayKitLifecycleListener
import app.cash.paykit.core.models.common.NetworkResult
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.response.CustomerTopLevelResponse
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.verify
import okhttp3.internal.notifyAll
import okhttp3.internal.wait
import org.junit.Before
import org.junit.Test

class CashAppPayKitStateTests {

  @MockK(relaxed = true)
  private lateinit var context: Context

  @MockK(relaxed = true)
  private lateinit var networkManager: NetworkManager

  private val mockLifecycleListener = MockLifecycleListener()

  @Before
  fun setup() {
    MockKAnnotations.init(this)
  }

  @Test
  fun `CreatingCustomerRequest State`() {
    val payKit = createPayKit()
    val listener = mockk<CashAppPayKitListener>(relaxed = true)
    payKit.registerForStateUpdates(listener)

    every { networkManager.createCustomerRequest(any(), any()) } returns NetworkResult.failure(
      Exception("bad"),
    )

    payKit.createCustomerRequest(FakeData.oneTimePayment)
    verify { listener.payKitStateDidChange(CreatingCustomerRequest) }
  }

  @Test
  fun `UpdatingCustomerRequest State`() {
    val payKit = createPayKit()
    val listener = mockk<CashAppPayKitListener>(relaxed = true)
    payKit.registerForStateUpdates(listener)

    every {
      networkManager.updateCustomerRequest(
        any(),
        any(),
        any(),
      )
    } returns NetworkResult.failure(
      Exception("bad"),
    )
    payKit.updateCustomerRequest("abc", FakeData.oneTimePayment)
    verify { listener.payKitStateDidChange(UpdatingCustomerRequest) }
  }

  @Test
  fun `PollingTransactionStatus State`() {
    val payKit = createPayKit(Authorizing)
    val listener = mockk<CashAppPayKitListener>(relaxed = true)
    payKit.registerForStateUpdates(listener)
    mockLifecycleListener.simulateOnApplicationForegrounded()
    verify { listener.payKitStateDidChange(PollingTransactionStatus) }
  }

  @Test
  fun `ReadyToAuthorize State`() {
    val payKit = createPayKit()
    val listener = mockk<CashAppPayKitListener>(relaxed = true)
    payKit.registerForStateUpdates(listener)
    val customerTopLevelResponse: NetworkResult.Success<CustomerTopLevelResponse> = mockk()
    val customerResponseData: CustomerResponseData = mockk(relaxed = true)
    every { customerTopLevelResponse.data.customerResponseData } returns customerResponseData
    every {
      networkManager.createCustomerRequest(
        any(),
        any(),
      )
    } returns customerTopLevelResponse

    payKit.createCustomerRequest(FakeData.oneTimePayment)
    verify { listener.payKitStateDidChange(ofType(ReadyToAuthorize::class)) }
  }

  @Test
  fun `Approved State`() {
    val starterCustomerResponseData: CustomerResponseData = mockk(relaxed = true)
    val payKit = createPayKit(Authorizing, starterCustomerResponseData)
    val payKitListener = MockCashAppPayKitListener()
    payKit.registerForStateUpdates(payKitListener)

    // Mock necessary network response.
    val customerTopLevelResponse: NetworkResult.Success<CustomerTopLevelResponse> = mockk()
    val customerResponseData: CustomerResponseData = mockk()
    every { customerResponseData.status } returns "APPROVED"
    every { customerTopLevelResponse.data.customerResponseData } returns customerResponseData
    every {
      networkManager.retrieveUpdatedRequestData(
        any(),
        any(),
      )
    } returns customerTopLevelResponse

    // Initiate polling routine, and wait for thread to return.
    mockLifecycleListener.simulateOnApplicationForegrounded()
    synchronized(payKitListener) {
      payKitListener.wait()
    }

    // Verify we got the expected result.
    assertThat(payKitListener.state).isInstanceOf(Approved::class.java)
  }

  @Test
  fun `Declined State`() {
    val starterCustomerResponseData: CustomerResponseData = mockk(relaxed = true)
    val payKit = createPayKit(Authorizing, starterCustomerResponseData)
    val payKitListener = MockCashAppPayKitListener()
    payKit.registerForStateUpdates(payKitListener)

    // Mock necessary network response.
    val customerTopLevelResponse: NetworkResult.Success<CustomerTopLevelResponse> = mockk()
    val customerResponseData: CustomerResponseData = mockk()
    every { customerResponseData.status } returns "DECLINED"
    every { customerTopLevelResponse.data.customerResponseData } returns customerResponseData
    every {
      networkManager.retrieveUpdatedRequestData(
        any(),
        any(),
      )
    } returns customerTopLevelResponse

    // Initiate polling routine, and wait for thread to return.
    mockLifecycleListener.simulateOnApplicationForegrounded()
    synchronized(payKitListener) {
      payKitListener.wait()
    }

    // Verify we got the expected result.
    assertThat(payKitListener.state).isInstanceOf(Declined::class.java)
  }

  @Test
  fun `Authorizing State`() {
    val payKit = createPayKit()
    val customerResponseData = mockk<CustomerResponseData>(relaxed = true) {
      every { authFlowTriggers } returns mockk {
        every { mobileUrl } returns "http://url"
      }
    }
    val listener = mockk<CashAppPayKitListener>(relaxed = true)
    payKit.registerForStateUpdates(listener)

    payKit.authorizeCustomerRequest(context, customerResponseData)

    verify { context.startActivity(any()) }
    verify { listener.payKitStateDidChange(Authorizing) }
  }

  private fun createPayKit(
    initialState: PayKitState = NotStarted,
    initialCustomerResponseData: CustomerResponseData? = null,
  ) =
    CashAppPayKitImpl(
      clientId = FakeData.CLIENT_ID,
      networkManager = networkManager,
      payKitLifecycleListener = mockLifecycleListener,
      useSandboxEnvironment = true,
      initialState = initialState,
      initialCustomerResponseData = initialCustomerResponseData,
      analytics = mockk(relaxed = true),
    )

  /**
   * Specialized Mock [PayKitLifecycleObserver] that we can easily simulate the following events:
   * - `onApplicationForegrounded`
   * - `onApplicationBackgrounded`
   */
  private class MockLifecycleListener : PayKitLifecycleObserver {
    private var listener: PayKitLifecycleListener? = null

    fun simulateOnApplicationForegrounded() {
      listener?.onApplicationForegrounded()
    }

    fun simulateOnApplicationBackgrounded() {
      listener?.onApplicationBackgrounded()
    }

    override fun register(newInstance: PayKitLifecycleListener) {
      listener = newInstance
    }

    override fun unregister(instanceToRemove: PayKitLifecycleListener) {
      listener = null
    }
  }

  /**
   * Our own Mock [CashAppPayKitListener] listener, that allows us to wait on a new state before continuing test execution.
   */
  internal class MockCashAppPayKitListener : CashAppPayKitListener {
    var state: PayKitState? = null

    override fun payKitStateDidChange(newState: PayKitState) {
      state = newState
      synchronized(this) { notifyAll() }
    }
  }
}
