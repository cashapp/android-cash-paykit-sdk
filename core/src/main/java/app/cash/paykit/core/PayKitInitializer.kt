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

import android.content.Context
import androidx.startup.Initializer
import app.cash.paykit.core.android.ApplicationContextHolder

internal interface PayKitInitializerStub

internal class PayKitInitializer : Initializer<PayKitInitializerStub> {
  override fun create(context: Context): PayKitInitializerStub {
    ApplicationContextHolder.init(context.applicationContext)
    return object : PayKitInitializerStub {}
  }

  override fun dependencies(): List<Class<out Initializer<*>>> {
    return emptyList()
  }
}
