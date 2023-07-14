package app.cash.paykit.core.ui

import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class CashAppPayButtonTest {
  @get:Rule
  var paparazzi = Paparazzi()

  @Test
  fun renderDefaultState() {
    val view = CashAppPayButton(paparazzi.context)
    paparazzi.snapshot(view)
  }

  @Test
  fun renderDisabledState() {
    val view = CashAppPayButton(paparazzi.context)
    view.isEnabled = false
    paparazzi.snapshot(view)
  }

}