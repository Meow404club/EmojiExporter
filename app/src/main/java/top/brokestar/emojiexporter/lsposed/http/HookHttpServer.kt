package top.brokestar.emojiexporter.lsposed.http

import android.util.Log
import top.brokestar.emojiexporter.lsposed.http.handlers.HealthHandler
import top.brokestar.emojiexporter.lsposed.http.handlers.QqEmoticonHandler
import top.brokestar.emojiexporter.lsposed.http.handlers.QqEmojiItemsHandler
import top.brokestar.emojiexporter.lsposed.http.handlers.QqSearchHandler
import top.brokestar.emojiexporter.lsposed.http.handlers.QqTicketHandler
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.DataInputStream
import java.nio.charset.StandardCharsets

class HookHttpServer(port: Int, private val secretToken: String) : NanoHTTPD("127.0.0.1", port) {
    companion object { private const val TAG = "HookHttpServer" }
    private val routes: Map<String, HttpHandler> = buildRoutes()
    private fun buildRoutes(): Map<String, HttpHandler> = linkedMapOf(
        "/health" to HealthHandler(secretToken),
        "/qq/ticket" to QqTicketHandler(secretToken),
        "/qq/sign" to QqTicketHandler(secretToken),
        "/qq/emoticon/tabs" to QqEmoticonHandler(secretToken),
        "/qq/emoticon/search" to QqSearchHandler(secretToken),
        "/qq/emoticon/items" to QqEmojiItemsHandler(secretToken),
        "/qq/emoticon/image" to QqEmojiItemsHandler(secretToken),
    )
    fun startServer() {
        if (!isAlive) { start(SOCKET_READ_TIMEOUT, false); Log.i(TAG, "started on 127.0.0.1:" + listeningPort) }
        else Log.w(TAG, "already alive")
    }
    fun stopServer() { if (isAlive) { stop(); Log.i(TAG, "stopped") } }
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.i(TAG, "req URI=" + uri + " method=" + session.method)
        val handler = routes[uri] ?: return notFound(uri)
        val body = if (session.method == Method.POST || session.method == Method.PUT) readPostBodyUtf8(session) else null
        return try { handler.handle(session, body) } catch (e: Exception) {
            Log.e(TAG, "handler error " + uri, e)
            NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, ServerCommon.MIME_JSON, JSONObject().apply { put("status","error"); put("message", e.localizedMessage ?: "unknown"); put("uri", uri) }.toString())
        }
    }
    private fun notFound(uri: String): Response {
        val body = JSONObject().apply { put("status","not_found"); put("message","URI not found: " + uri) }.toString()
        return newFixedLengthResponse(Response.Status.NOT_FOUND, ServerCommon.MIME_JSON, body)
    }
    private fun readPostBodyUtf8(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        return try {
            val buf = ByteArray(len)
            DataInputStream(session.inputStream).readFully(buf)
            String(buf, StandardCharsets.UTF_8)
        } catch (e: Exception) { Log.e(TAG, "read body failed", e); "" }
    }
}
