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
package app.cash.paykit.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageButton
import app.cash.paykit.core.R

/**
 * Cash App Pay button. Should be used in conjunction with either `CAPButtonStyle.Light` or `CAPButtonStyle.Dark` styles.
 *
 * **Note**: Due to its unmanaged nature, the button is merely a stylized button, it's up to developers
 * to trigger the correct action on button press, as well as manage any visibility states of the
 * button accordingly.
 */
class CashAppPayButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, style: Int = 0) :
  ImageButton(
    context,
    attrs,
    0,
    R.style.CAPButtonStyle_Dark
  ) {

  override fun setEnabled(enabled: Boolean) {
    super.setEnabled(enabled)
    alpha = if (enabled) {
      1f
    } else {
      .3f
    }
  }
}
