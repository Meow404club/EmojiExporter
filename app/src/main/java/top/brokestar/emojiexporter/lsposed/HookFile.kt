package top.brokestar.emojiexporter.lsposed

import android.util.Log
import java.io.File

object HookFile {
    private const val TAG = "EmojiFile"
    const val NAME_INSTALLED = "emoji_installed.json"
    const val NAME_LAST_SEARCH = "emoji_last_search.json"
    fun installedFile(): File? = fileFor(NAME_INSTALLED)
    fun lastSearchFile(): File? = fileFor(NAME_LAST_SEARCH)
    fun fileFor(name: String): File? {
        val ctx = QqRuntime.appContext
        return try {
            when {
                ctx != null -> {
                    val f = File(ctx.filesDir, name)
                    Log.i(TAG, "fileFor " + name + " -> " + f.absolutePath)
                    f
                }
                else -> {
                    val at = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
                    if (at != null) File(at.filesDir, name) else null
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "fileFor failed", e)
            null
        }
    }
    fun writeJson(name: String, json: String, logger: Any?) {
        try {
            val f = fileFor(name) ?: run { log(logger, true, "writeJson no file for " + name); return }
            f.parentFile?.mkdirs()
            f.writeText(json)
            try { Runtime.getRuntime().exec(arrayOf("chmod", "644", f.absolutePath)) } catch (_: Throwable) {}
            log(logger, false, "persisted " + f.absolutePath + " (" + json.length + ")")
        } catch (e: Throwable) { log(logger, true, "writeJson " + name + " failed " + e.message) }
    }
    fun readJson(name: String): String? = try {
        val f = fileFor(name) ?: return null
        if (!f.exists()) return null
        f.readText().takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }
    private fun log(logger: Any?, isWarn: Boolean, msg: String) {
        if (isWarn) Log.w(TAG, msg) else Log.i(TAG, msg)
        try {
            val m = if (isWarn) "w" else "i"
            logger?.javaClass?.methods?.firstOrNull { it.name == m && it.parameterCount == 1 }?.invoke(logger, msg)
        } catch (_: Throwable) {}
    }
}
