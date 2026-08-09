package top.brokestar.emojiexporter.data

import com.topjohnwu.superuser.Shell

/** 枚举设备上已登录的 QQ 号（第一层·元数据：账号范围） */
object QQUinResolver {
    private const val QQ_PKG = "com.tencent.mobileqq"
    fun listUins(): List<String> {
        val out = mutableListOf<String>()
        // KernelSU/Magisk 对 root app 做 mount namespace 隔离，看不到其他 app 的 /data/data。
        // nsenter -t 1 -m 进入 init 的 mount namespace 即可访问全局文件系统。
        // 1) databases/*.db 文件名即 uin
        Shell.cmd("nsenter -t 1 -m ls /data/data/$QQ_PKG/databases/*.db 2>/dev/null").exec().out.forEach {
            val name = it.substringAfterLast('/').removeSuffix(".db")
            if (name.all { c -> c.isDigit() } && name.length in 5..15) out += name
        }
        // 2) shared_prefs 兜底（跨版本）
        if (out.isEmpty()) {
            Shell.cmd("nsenter -t 1 -m ls /data/data/$QQ_PKG/shared_prefs/ 2>/dev/null").exec().out.forEach {
                val u = it.removeSuffix(".xml").trim()
                if (u.all { c -> c.isDigit() } && u.length in 5..15) out += u
            }
        }
        return out.distinct()
    }
}
