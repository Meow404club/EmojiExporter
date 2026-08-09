package top.brokestar.emojiexporter.lsposed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * IPC bridge: QQ 进程写入 /data/local/tmp，App 进程通过 Shell cat 轮询；
 * ContentProvider.call/query 保留但不再作为 App 发现的主路径（避免 authority 回环：App 的 provider 会截获自己的 query）。
 * QQ 侧 authorities: top.brokestar.emojiexporter  (Hook 进程)
 * App 侧 authorities: top.brokestar.emojiexporter.app  (避免自回环)
 */
class IpcBridge : ContentProvider() {
    companion object {
        private const val TAG = "EmojiHook/Ipc"
        fun start() { Log.i(TAG, "IpcBridge registered (QQ side authority=top.brokestar.emojiexporter)") }
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        Log.i(TAG, "query $uri pid=${android.os.Process.myPid()}")
        return when (uri.path) {
            "/packs" -> packsCursor()
            "/search" -> searchCursor(uri.getQueryParameter("keyword") ?: "")
            else -> null
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.i(TAG, "call $method arg=$arg pid=${android.os.Process.myPid()}")
        return when (method) {
            "getInstalled" -> Bundle().apply {
                val list = InstalledPacksHook.fetchActive().ifEmpty { InstalledPacksHook.getInstalled() }
                putString("json", list.joinToString(",", "[", "]") { "{\"epId\":\"${it.epId}\",\"name\":\"${it.name ?: ""}\"}" })
            }
            "triggerSearch" -> Bundle().apply {
                val kw = arg ?: extras?.getString("keyword") ?: ""
                val ok = StoreSearchHook.triggerSearch(kw)
                putBoolean("ok", ok)
            }
            "getLastSearch" -> Bundle().apply {
                val (kw, items) = StoreSearchHook.getLastSearch()
                putString("keyword", kw)
                putString("json", items.joinToString(",", "[", "]") { "{\"epId\":\"${it["epId"]}\",\"eId\":\"${it["eId"]}\"}" })
            }
            else -> null
        }
    }

    private fun packsCursor(): Cursor {
        val packs = InstalledPacksHook.fetchActive().ifEmpty { InstalledPacksHook.getInstalled() }
        val c = MatrixCursor(arrayOf("epId", "name", "coverUrl"))
        packs.forEach { c.addRow(arrayOf(it.epId, it.name ?: "", it.coverUrl ?: "")) }
        return c
    }

    private fun searchCursor(keyword: String): Cursor {
        val (_, items) = StoreSearchHook.getLastSearch()
        if (keyword.isNotBlank() && keyword != StoreSearchHook.getLastSearch().first) {
            StoreSearchHook.triggerSearch(keyword)
        }
        val c = MatrixCursor(arrayOf("epId", "eId"))
        items.forEach { c.addRow(arrayOf(it["epId"] ?: "", it["eId"] ?: "")) }
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
