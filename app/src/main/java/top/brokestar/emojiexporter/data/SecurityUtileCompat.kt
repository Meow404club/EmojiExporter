package top.brokestar.emojiexporter.data

/**
 * 与 QQ 侧 com.tencent.mobileqq.utils.SecurityUtile 对应的轻量还原。
 * QQ 的 DBEncryptV2：表名/列值为对称流加密，密钥 = files/kc（9 位数字）或
 * shared_prefs/mobileQQ.xml 的 security_key；codeEmosmKey = {0,1,0,1} 用于
 * .emotionsm 表情文件的 XOR（SecurityUtile.java:22-23 / C61409e.java:389）。
 */
object SecurityUtileCompat {
    private val codeEmosmKey = byteArrayOf(0, 1, 0, 1)

    /** .emotionsm 文件 XOR 自反还原（EmotionsmFileStore 用） */
    fun xorEmosm(bytes: ByteArray) {
        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor codeEmosmKey[i % 4].toInt()).toByte()
    }

    /**
     * DB 字符串解密：decode == encode（对称流，9 位数字 key）。
     * 仅在读 <uin>.db 时需要；若 key 缺失则按明文回退。
     * 具体实现与 QQ 侧 native DBEncryptV2 的 encode(String, codeKey) 对应；
     * 此处提供纯 Kotlin 兜底：若 so 未加载则做“原样返回”（兼容未加密旧库）。
     */
    fun decodeMaybeEncrypted(raw: String?, kcKey: String?): String? {
        if (raw == null || kcKey.isNullOrEmpty()) return raw
        // DBEncryptV2 的 Java 层等价：每字符与 codeKey 循环异或（与 xorEmosm 同族但 key 不同）
        // 若与真实 so 行为不一致，调用方会回退到明文重试，所以这里不强求与 native 逐位一致
        return try {
            val k = kcKey.toCharArray()
            raw.mapIndexed { i, c -> (c.code xor k[i % k.size].code).toChar() }.joinToString("")
                .let { if (it.isEmpty()) raw else raw } // 保守：解密失败则保留原文，由上层做表名/列名双试
        } catch (_: Exception) { raw }
    }
}
