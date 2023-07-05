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
package app.cash.paykit.core.models.pii

/**
 * A string that has been classified as Personal Identifiable Information (PII).
 *
 */
class PiiString(private var value: String) {

  /**
   * Returns the redacted value of this PiiString.
   */
  fun getRedacted(): String {
    return "redacted"
  }

  /**
   * Get the plain-text version of this PiiString.
   * It is important that you do not log or store this value. Use `getRedacted()` instead if necessary.
   */
  override fun toString(): String {
    return value
  }
}
