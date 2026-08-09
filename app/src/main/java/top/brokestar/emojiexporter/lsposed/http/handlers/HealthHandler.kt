package top.brokestar.emojiexporter.lsposed.http.handlers

import top.brokestar.emojiexporter.lsposed.QqRuntime
import top.brokestar.emojiexporter.lsposed.http.BaseHandler
import top.brokestar.emojiexporter.lsposed.http.ServerCommon
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.json.JSONObject

class HealthHandler(token: String) : BaseHandler(token) {
    override fun onGet(session: IHTTPSession): NanoHTTPD.Response {
        val ready = QqRuntime.classLoader != null && QqRuntime.appContext != null
        val body = JSONObject().apply {
            put("status", if (ready) "ok" else "starting")
            put("hookReady", ready)
            put("classLoader", QqRuntime.classLoader?.toString() ?: "null")
            put("packageName", QqRuntime.packageName ?: "null")
            put("filesDir", runCatching { QqRuntime.appContext?.filesDir?.absolutePath }.getOrNull() ?: "null")
        }.toString()
        return if (ready) okRaw(body) else NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, ServerCommon.MIME_JSON, body)
    }
}
