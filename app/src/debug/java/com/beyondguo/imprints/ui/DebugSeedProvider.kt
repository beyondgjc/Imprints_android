package com.beyondguo.imprints.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * **仅调试用**：借 ContentProvider 在进程启动时自注册 adb 调试钩子，
 * 这样主源集与生产清单都不用为调试能力留任何痕迹。
 *
 * 只存在于 debug 源集，并由 `src/debug/AndroidManifest.xml` 注册。
 */
class DebugSeedProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { DebugSeed.install(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
