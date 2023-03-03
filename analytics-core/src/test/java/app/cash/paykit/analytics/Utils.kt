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

package app.cash.paykit.analytics

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.cash.paykit.analytics.persistence.AnalyticEntry
import app.cash.paykit.analytics.persistence.sqlite.AnalyticsSQLiteDataSource
import app.cash.paykit.analytics.persistence.sqlite.AnalyticsSqLiteHelper
import java.lang.reflect.Field

internal object Utils {

  fun getPrivateField(obj: Any, fieldName: String?): Any? {
    try {
      val field: Field = obj.javaClass.getDeclaredField(fieldName)
      field.isAccessible = true
      return field.get(obj)
    } catch (e: NoSuchFieldException) {
      e.printStackTrace()
    } catch (e: IllegalAccessException) {
      e.printStackTrace()
    }
    return null
  }

  fun createEntry(entryType: String?, entryState: Int): AnalyticEntry {
    return createEntry(
      "entry.process.id",
      entryType,
      entryState,
      "entry.load",
      "entry.metadata",
      "v1",
    )
  }

  fun createEntry(
    processId: String?,
    entryType: String?,
    entryState: Int,
    load: String,
    metaData: String?,
    version: String?,
  ) = AnalyticEntry(
    id = System.currentTimeMillis(),
    processId = processId,
    type = entryType,
    state = entryState,
    content = load,
    metaData = metaData,
    version = version,
  )

  fun getEntriesToSync(count: Int): List<AnalyticEntry> {
    return mutableListOf<AnalyticEntry>().apply {
      for (i in 0 until count) {
        add(createEntry("TYPE_1", AnalyticEntry.STATE_NEW))
      }
    }
  }

  /**
   * Returns all sync entries from the database.
   *
   * @return list of sync entries
   */
  fun getAllSyncEntries(sqLiteHelper: AnalyticsSqLiteHelper): List<AnalyticEntry> {
    val entries = mutableListOf<AnalyticEntry>()
    val database: SQLiteDatabase = sqLiteHelper.database
    var cursor: Cursor? = null
    try {
      // @formatter:off
      cursor = database.query(
        true,
        AnalyticsSQLiteDataSource.TABLE_SYNC_ENTRIES,
        null,
        null,
        null,
        null,
        null,
        "id ASC",
        null,
      )
      // @formatter:on
      cursor.moveToFirst()
      while (!cursor.isAfterLast) {
        entries.add(AnalyticEntry.from(cursor))
        cursor.moveToNext()
      }
    } catch (e: Exception) {
      e.printStackTrace()
      Log.e("", "", e)
    } finally {
      cursor?.close()
    }
    return entries
  }

  /**
   * Deletes all entries from the database.
   */
  @Synchronized
  fun deleteAllEntries(sqLiteHelper: AnalyticsSqLiteHelper) {
    val database: SQLiteDatabase = sqLiteHelper.database
    try {
      database.delete(AnalyticsSQLiteDataSource.TABLE_SYNC_ENTRIES, null, null)
    } catch (e: Exception) {
      e.printStackTrace()
      Log.e("", "", e)
    }
  }

  fun insertSyncEntry(
    sqLiteHelper: AnalyticsSqLiteHelper,
    processId: String?,
    entryType: String?,
    entryState: Int,
    load: String?,
    metaData: String?,
    version: String?,
  ): Long {
    var insertId: Long = -1
    try {
      val database: SQLiteDatabase = sqLiteHelper.database
      val values = ContentValues()
      values.put(AnalyticsSQLiteDataSource.COLUMN_TYPE, entryType)
      values.put(AnalyticsSQLiteDataSource.COLUMN_PROCESS_ID, processId)
      values.put(AnalyticsSQLiteDataSource.COLUMN_CONTENT, load)
      values.put(AnalyticsSQLiteDataSource.COLUMN_STATE, entryState)
      values.put(AnalyticsSQLiteDataSource.COLUMN_META_DATA, metaData)
      values.put(AnalyticsSQLiteDataSource.COLUMN_VERSION, version)
      insertId = database.insert(AnalyticsSQLiteDataSource.TABLE_SYNC_ENTRIES, null, values)
      if (insertId < 0) {
        Log.e(
          "Utils",
          "Unable to insert record into the " + AnalyticsSQLiteDataSource.TABLE_SYNC_ENTRIES + ", values: " +
            values,
        )
      }
    } catch (e: Exception) {
      e.printStackTrace()
      Log.e("", "", e)
    }
    return insertId
  }
}
