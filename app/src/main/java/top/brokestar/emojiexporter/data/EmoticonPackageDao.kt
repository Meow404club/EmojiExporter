package top.brokestar.emojiexporter.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** EmoticonPackage 实体（表名 = 类名，见 persistence/Entity.java:64） */
data class EmoticonPackage(
    val epId: String, val name: String?, val coverUrl: String?,
    val status: Int, val valid: Boolean, val updateFlag: Int,
)

object EmoticonPackageDao {
    /** 读取已添加/有效包；对表名/列名做 加密名/明文名 双试（兼容 DBEncryptV2） */
    fun listInstalled(db: SQLiteDatabase, kcKey: String?): List<EmoticonPackage> {
        val tableCandidates = listOf("EmoticonPackage", SecurityUtileCompat.decodeMaybeEncrypted("EmoticonPackage", kcKey) ?: "EmoticonPackage")
        for (table in tableCandidates.distinct()) {
            try { return queryTable(db, table) } catch (_: Exception) { /* 试下一个表名 */ }
        }
        return emptyList()
    }

    private fun queryTable(db: SQLiteDatabase, table: String): List<EmoticonPackage> {
        val out = mutableListOf<EmoticonPackage>()
        // 先探列名，兼容不同版本字段缺失
        val cols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) cols += c.getString(1)
        }
        if (cols.isEmpty()) throw IllegalStateException("no table $table")
        fun col(vararg names: String) = names.firstOrNull { it in cols }
        val cEpId = col("epId", "epid", "ep_id") ?: return out
        val cName = col("name", "packageName", "epName")
        val cCover = col("coverUrl", "cover", "iconUrl")
        val cStatus = col("status"); val cValid = col("valid"); val cFlag = col("updateFlag", "update_flag")
        val sel = buildString {
            append("SELECT $cEpId")
            if (cName != null) append(",$cName")
            if (cCover != null) append(",$cCover")
            if (cStatus != null) append(",$cStatus")
            if (cValid != null) append(",$cValid")
            if (cFlag != null) append(",$cFlag")
            append(" FROM $table")
        }
        db.rawQuery(sel, null).use { cur ->
            while (cur.moveToNext()) {
                val epId = cur.getString(0) ?: continue
                var idx = 1
                val name = if (cName != null) cur.getString(idx++) else null
                val cover = if (cCover != null) cur.getString(idx++) else null
                val status = if (cStatus != null) cur.getInt(idx++) else 2
                val valid = if (cValid != null) (cur.getInt(idx++) != 0) else true
                val flag = if (cFlag != null) cur.getInt(idx++) else 0
                if (!valid) continue
                out += EmoticonPackage(epId, name, cover, status, valid, flag)
            }
        }
        return out
    }
}
private inline fun Cursor.use(block: (Cursor) -> Unit) { try { block(this) } finally { close() } }
