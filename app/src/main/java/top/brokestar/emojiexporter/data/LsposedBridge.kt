package top.brokestar.emojiexporter.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.topjohnwu.superuser.Shell
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object LsposedBridge {
    private const val AUTHORITY_QQ = "top.brokestar.emojiexporter"
    private const val FILE_INSTALLED = "/data/local/tmp/emoji_installed.json"
    private const val FILE_LAST_SEARCH = "/data/local/tmp/emoji_last_search.json"
    private const val QQ_FILES_INSTALLED = "/data/data/com.tencent.mobileqq/files/emoji_installed.json"
    private const val QQ_FILES_LAST_SEARCH = "/data/data/com.tencent.mobileqq/files/emoji_last_search.json"

    private fun queryPacksQQ(ctx: Context): Cursor? = try { ctx.contentResolver.query(Uri.parse("content://$AUTHORITY_QQ/packs"), null, null, null, null) } catch (_: Exception) { null }
    private fun callQQ(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle? = try { ctx.contentResolver.call(Uri.parse("content://$AUTHORITY_QQ"), method, arg, extras) } catch (_: Exception) { null }

    fun getInstalled(ctx: Context): List<EpMeta>? {
        try { QqMallApi.fetchTabsHttp(ctx, 0, false)?.let { if (it.isNotEmpty()) return it } } catch (_: Throwable) {}
        readInstalledFromFile()?.let { if (it.isNotEmpty()) return it }
        try { val c = queryPacksQQ(ctx); if (c != null) { val out = mutableListOf<EpMeta>(); c.use { val iEp = it.getColumnIndex("epId"); val iName = it.getColumnIndex("name"); val iCover = it.getColumnIndex("coverUrl"); while (it.moveToNext()) out += EpMeta(it.getString(iEp) ?: continue, it.getString(iName)?.takeIf { s -> s.isNotEmpty() }, it.getString(iCover)?.takeIf { s -> s.isNotEmpty() }) }; if (out.isNotEmpty()) return out } } catch (_: Exception) {}
        try { val b = callQQ(ctx, "getInstalled", null, null); val json = b?.getString("json"); if (!json.isNullOrBlank()) parseEpJson(json)?.let { if (it.isNotEmpty()) return it } } catch (_: Exception) {}
        try { QqMallApi.fetchTabsHttp(ctx, 0, true)?.let { if (it.isNotEmpty()) return it } } catch (_: Throwable) {}
        return readInstalledFromFile()
    }

    private fun readInstalledFromFile(): List<EpMeta>? = try {
        val txt = Shell.cmd("nsenter -t 1 -m cat $QQ_FILES_INSTALLED 2>/dev/null; nsenter -t 1 -m cat $FILE_INSTALLED 2>/dev/null").exec().out.joinToString("")
        if (txt.isBlank() || txt.length < 5) return null
        parseEpJson(txt) ?: parseEpRegex(txt)
    } catch (_: Exception) { null }

    private fun parseEpJson(txt: String): List<EpMeta>? = try {
        val t = txt.trim()
        if (t.startsWith("[")) {
            val arr = JSONArray(t)
            val out = mutableListOf<EpMeta>()
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val epId = o.optString("epId"); if (epId.isNotBlank()) out.add(EpMeta(epId, o.optString("name").takeIf { it.isNotBlank() }, o.optString("coverUrl").takeIf { it.isNotBlank() })) }
            out.takeIf { it.isNotEmpty() }
        } else {
            val jo = JSONObject(t)
            val arr = jo.optJSONArray("packs") ?: jo.optJSONArray("items") ?: return null
            val out = mutableListOf<EpMeta>()
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val epId = o.optString("epId"); if (epId.isNotBlank()) out.add(EpMeta(epId, o.optString("name").takeIf { it.isNotBlank() }, o.optString("coverUrl").takeIf { it.isNotBlank() })) }
            out.takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) { null }

    private fun parseEpRegex(txt: String): List<EpMeta>? = try {
        val re = Regex("\"epId\"\\s*:\\s*\"(\\d+)\"")
        re.findAll(txt).map { m -> val epId = m.groupValues[1]; val seg = txt.substring(m.range.first, minOf(txt.length, m.range.first + 500)); val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(seg)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }; EpMeta(epId, name, null) }.toList().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    fun triggerSearch(ctx: Context, keyword: String): Boolean {
        try { QqMallApi.fetchSearchHttp(ctx, keyword)?.let { if (it.items.isNotEmpty()) return true } } catch (_: Throwable) {}
        try { if (callQQ(ctx, "triggerSearch", keyword, Bundle())?.getBoolean("ok") == true) return true } catch (_: Exception) {}
        return try { Shell.cmd("echo \"" + keyword + "\" > /data/local/tmp/emoji_search_trigger.txt 2>/dev/null; chmod 666 /data/local/tmp/emoji_search_trigger.txt 2>/dev/null").exec(); true } catch (_: Exception) { false }
    }

    fun getLastSearch(ctx: Context): List<EpMeta>? {
        try { val b: Bundle? = callQQ(ctx, "getLastSearch", null, null); val json = b?.getString("json"); if (!json.isNullOrBlank()) parseEpJson(json)?.let { if (it.isNotEmpty()) return it } } catch (_: Exception) {}
        return readLastSearchFromFile()
    }

    fun getLastSearchForKeyword(ctx: Context, keyword: String): List<EpMeta>? {
        try { QqMallApi.fetchSearchHttp(ctx, keyword)?.let { if (it.items.isNotEmpty()) return it.items } } catch (_: Throwable) {}
        return getLastSearch(ctx)
    }

    private fun readLastSearchFromFile(): List<EpMeta>? = try {
        val txt = Shell.cmd("nsenter -t 1 -m cat $QQ_FILES_LAST_SEARCH 2>/dev/null; nsenter -t 1 -m cat $FILE_LAST_SEARCH 2>/dev/null").exec().out.joinToString("")
        if (txt.isBlank()) return null
        parseEpJson(txt) ?: Regex("\"epId\"\\s*:\\s*\"(\\d+)\"").findAll(txt).map { EpMeta(it.groupValues[1], null, null) }.toList().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}
