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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.cash.paykit.core.NetworkManager
import app.cash.paykit.core.exceptions.CashAppPayIntegrationException
import app.cash.paykit.core.models.common.NetworkResult
import app.cash.paykit.core.models.common.NetworkResult.Failure
import app.cash.paykit.core.state.PayKitEvents.CreateCustomerRequestEvent
import app.cash.paykit.core.state.PayKitEvents.DeepLinkError
import app.cash.paykit.core.state.PayKitEvents.DeepLinkSuccess
import app.cash.paykit.core.state.PayKitEvents.GetCustomerRequestEvent
import app.cash.paykit.core.state.PayKitEvents.StartWithExistingCustomerRequestEvent
import app.cash.paykit.core.state.PayKitEvents.UpdateCustomerRequestEvent
import ru.nsk.kstatemachine.IState
import ru.nsk.kstatemachine.onEntry
import ru.nsk.kstatemachine.onExit
import ru.nsk.kstatemachine.processEventBlocking
import kotlin.time.Duration

interface PayKitWorker {
  fun startWithExistingRequest(state: IState, context: PayKitContext)

  /**
   * @param state - the IState node to attach the lifecycle events to
   * @param interval - the interval duration frequency at which we should poll
   */
  fun poll(state: IState, context: PayKitContext, interval: Duration)

  fun createCustomerRequest(state: IState, context: PayKitContext)
  fun updateCustomerRequest(state: IState, context: PayKitContext)

  /**
   * @param state - the IState node to attach the lifecycle events to
   */
  fun deepLinkToCashApp(state: IState, context: PayKitContext)
}

internal class RealPayKitWorker(
  private val clientId: String,
  private val networkManager: NetworkManager,
  private val context: Context,
) : PayKitWorker {
  override fun deepLinkToCashApp(
    state: IState,
    paykitContext: PayKitContext
  ) {
    with(state) {
      onEntry {
        Thread {
          // Open Mobile URL provided by backend response.
          val intent = Intent(Intent.ACTION_VIEW)
          intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
          val mobileUrl = paykitContext.customerResponseData!!.authFlowTriggers?.mobileUrl
          intent.data = try {
            Uri.parse(mobileUrl)
          } catch (error: NullPointerException) {
            machine.processEventBlocking(DeepLinkError(IllegalArgumentException("Cannot parse redirect url")))
            return@Thread
          }

          try {
            context.startActivity(intent)
            machine.processEventBlocking(DeepLinkSuccess)
          } catch (activityNotFoundException: ActivityNotFoundException) {
            machine.processEventBlocking(DeepLinkError(CashAppPayIntegrationException("Unable to open mobileUrl: ${mobileUrl}")))
          }

        }.start()
      }
    }
  }

  override fun updateCustomerRequest(state: IState, context: PayKitContext) {
    with(state) {
      onEntry {
        Thread {
          try {
            val networkResult = networkManager.updateCustomerRequest(
              clientId = clientId,
              requestId = context.updateCustomerRequestData!!.requestId,
              context.updateCustomerRequestData!!.actions,
            )
            when (networkResult) {
              is Failure -> {
                machine.processEventBlocking(
                  UpdateCustomerRequestEvent.Error(
                    networkResult.exception
                  )
                )
              }

              is NetworkResult.Success -> {
                val customerResponseData = networkResult.data.customerResponseData

                machine.processEventBlocking(
                  UpdateCustomerRequestEvent.Success(
                    customerResponseData
                  )
                )
              }
            }
          } catch (e: Exception) {
            machine.processEventBlocking(UpdateCustomerRequestEvent.Error(e))
          }
        }.apply {
          onExit(once = true) {
            Log.i("CRAIG", "Stopping ${getName()} thread!!!")
            interrupt()
          }
          name = "update-request-thread"
          start()
        }
      }
    }
  }

  override fun createCustomerRequest(state: IState, context: PayKitContext) {
    with(state) {
      onEntry {
        try {
          when (val networkResult =
            networkManager.createCustomerRequest(
              clientId = clientId,
              paymentActions = context.createCustomerRequestData!!.actions, // TODO validate context
              redirectUri = context.createCustomerRequestData!!.redirectUri,
            )
          ) {
            is Failure -> {
              machine.processEventBlocking(
                CreateCustomerRequestEvent.Error(networkResult.exception)

              )
            }

            is NetworkResult.Success -> {
              val customerResponseData = networkResult.data.customerResponseData
              machine.processEventBlocking(
                CreateCustomerRequestEvent.Success(customerResponseData),
              )
            }
          }
        } catch (e: Exception) {
          machine.processEventBlocking(CreateCustomerRequestEvent.Error(e))
        }
      }
      onExit {
        // TODO cancel network request?
      }
    }
  }

  override fun poll(
    state: IState,
    context: PayKitContext,
    interval: Duration,
  ) {
    with(state) {
      onEntry {
        Thread {
          try {
            while (true) {
              Thread.sleep(interval.inWholeMilliseconds)
              val networkResult = networkManager.retrieveUpdatedRequestData(
                clientId,
                context.customerResponseData!!.id
              )
              when (networkResult) {
                is Failure -> {
                  machine.processEventBlocking(
                    GetCustomerRequestEvent.Error(
                      networkResult.exception
                    )
                  )
                }

                is NetworkResult.Success -> {
                  val customerResponseData = networkResult.data.customerResponseData
                  machine.processEventBlocking(
                    GetCustomerRequestEvent.Success(
                      customerResponseData
                    )
                  )
                }
              }
            }
          } catch (e: InterruptedException) {
            Log.i("CRAIG", "InterruptedException on polling thread")
          }
        }.apply {
          name = "polling-${interval.inWholeSeconds}-seconds-thread"
          onExit(once = true) {
            interrupt()
            Log.i("CRAIG", "Exiting: Stopping ${getName()} thread!!!")
          }
          Log.i("CRAIG", "Entering: Starting $name thread!!!")
          start()
        }
      }
    }
  }

  override fun startWithExistingRequest(state: IState, context: PayKitContext) {
    with(state) {
      onEntry {
        Thread {
          try {
            val networkResult = networkManager.retrieveUpdatedRequestData(
              clientId,
              context.startWithExistingId!!,
            )
            when (networkResult) {
              is Failure -> {
                machine.processEventBlocking(
                  StartWithExistingCustomerRequestEvent.Error(networkResult.exception)
                )
              }

              is NetworkResult.Success -> {
                val customerResponseData = networkResult.data.customerResponseData

                machine.processEventBlocking(
                  StartWithExistingCustomerRequestEvent.Success(
                    customerResponseData
                  )
                )
              }
            }
          } catch (e: Exception) {
            machine.processEventBlocking(UpdateCustomerRequestEvent.Error(e))
          }
        }.apply {
          onExit(once = true) {
            Log.i("CRAIG", "Stopping ${getName()} thread!!!")
            interrupt()
          }
          name = "start-with-existing-request-thread"
          start()
        }
      }
    }
  }
}
