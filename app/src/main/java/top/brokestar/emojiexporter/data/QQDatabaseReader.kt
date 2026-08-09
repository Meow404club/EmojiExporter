package top.brokestar.emojiexporter.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.topjohnwu.superuser.Shell
import java.io.File

/** 第一层：用 root 把 /data/data/com.tencent.mobileqq/databases/<uin>.db 拷到 cache 再只读打开 */
object QQDatabaseReader {
    private const val QQ_PKG = "com.tencent.mobileqq"

    data class OpenedDb(val db: SQLiteDatabase, val tmpFile: File, val kcKey: String?)

    fun openForUin(ctx: Context, uin: String): OpenedDb? {
        val tmp = File(ctx.cacheDir, "${uin}.db.tmp")
        Shell.cmd("cp /data/data/$QQ_PKG/databases/${uin}.db ${tmp.absolutePath} 2>/dev/null; chmod 644 ${tmp.absolutePath}").exec()
        if (!tmp.exists() || tmp.length() == 0L) return null
        val kc = readKcKey()
        val db = try {
            SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (_: Exception) { return null }
        return OpenedDb(db, tmp, kc)
    }

    fun close(o: OpenedDb) { try { o.db.close() } catch (_: Exception) {}; try { o.tmpFile.delete() } catch (_: Exception) {} }

    private fun readKcKey(): String? {
        // files/kc 优先，其次 shared_prefs/mobileQQ.xml 的 security_key
        val r1 = Shell.cmd("cat /data/data/$QQ_PKG/files/kc 2>/dev/null").exec()
        val kc = r1.out.firstOrNull()?.trim()?.takeIf { it.length >= 9 }
        if (!kc.isNullOrEmpty()) return kc
        val r2 = Shell.cmd("cat /data/data/$QQ_PKG/shared_prefs/mobileQQ.xml 2>/dev/null | grep -o 'security_key[^<]*' | sed 's/.*>\\(.*\\)<.*/\\1/'").exec()
        return r2.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
