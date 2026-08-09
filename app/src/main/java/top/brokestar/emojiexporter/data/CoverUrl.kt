package top.brokestar.emojiexporter.data

/**
 * 表情包封面 URL 构造。
 *
 * QQ 服务端给 EmoticonPackage.imageUrl 基本不填，但 QQ 自己有固定 CDN 封面规则
 * （见 EmotionPanelConstans.emoticonRecommendUrl，shard = epId % 10）。
 * 这条 CDN 路径覆盖绝大多数表情包，且 Coil 能直接缓存，无需走 hook 解密端点。
 *
 * 对 QQ 内部专属包（如「叶洛洛」，CDN 上没有）走兜底：包内第一个表情的 hook 预览图。
 */
object CoverUrl {
    private const val CDN_HOST = "https://gxh.vip.qq.com/club/item/parcel/img/parcel"

    /** epId → CDN 封面绝对 URL。 */
    fun cdn(epId: String): String? {
        val id = epId.trim()
        if (id.isEmpty() || id.any { !it.isDigit() }) return null
        val shard = (id.toLongOrNull() ?: return null) % 10
        return "$CDN_HOST/$shard/$id/200x200.png"
    }

    /**
     * 综合取封面：服务端 imageUrl 优先（若可信），其次 CDN 模板，最后 hook 单图兜底。
     * @param hookFallback 由调用方提供「第一张表情 eId」，用于拼 hook 端点兜底（可空）。
     */
    fun resolve(
        ctx: android.content.Context,
        epId: String,
        imageUrl: String? = null,
        hookFallback: Pair<String, String>? = null, // (epId, eId)
    ): String? {
        if (!imageUrl.isNullOrBlank() && imageUrl.startsWith("http")) return imageUrl
        cdn(epId)?.let { return it }
        if (hookFallback != null) {
            return QqMallApi.imageUrl(ctx, hookFallback.first, hookFallback.second, "aio")
        }
        return null
    }
}
