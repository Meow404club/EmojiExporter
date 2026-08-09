package top.brokestar.emojiexporter.data

import com.topjohnwu.superuser.Shell
import java.io.File

/** .emotionsm 本地文件探测 + XOR 还原（SecurityUtile.codeEmosmKey={0,1,0,1}） */
object EmotionsmFileStore {
    private val roots = listOf(
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/.emotionsm",
        "/storage/emulated/0/Android/media/com.tencent.mobileqq/Tencent/MobileQQ/.emotionsm",
        "/storage/emulated/0/Tencent/MobileQQ/.emotionsm",
    )

    fun resolveRoot(): String? = roots.firstOrNull { Shell.cmd("test -d \"$it\" && echo ok").exec().out.contains("ok") }

    fun listEpIds(): List<String> {
        val root = resolveRoot() ?: return emptyList()
        return Shell.cmd("ls -1 \"$root\" 2>/dev/null").exec().out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun exists(epId: String, eId: String): Boolean {
        val root = resolveRoot() ?: return false
        return Shell.cmd("test -f \"$root/$epId/$eId\" && echo ok").exec().out.contains("ok")
    }

    /** 把 .emotionsm/[epId]/[eId] 还原后拷到 dst（dst 由调用方创建） */
    fun exportOne(epId: String, eId: String, dst: File): Boolean {
        val root = resolveRoot() ?: return false
        val src = "$root/$epId/$eId"
        val tmp = File(dst.parentFile, dst.name + ".enc.tmp")
        Shell.cmd("cp \"$src\" \"${tmp.absolutePath}\" 2>/dev/null").exec()
        if (!tmp.exists() || tmp.length() == 0L) return false
        return try {
            val bytes = tmp.readBytes()
            SecurityUtileCompat.xorEmosm(bytes)
            dst.parentFile?.mkdirs(); dst.writeBytes(bytes); true
        } finally { try { tmp.delete() } catch (_: Exception) {} }
    }
}
