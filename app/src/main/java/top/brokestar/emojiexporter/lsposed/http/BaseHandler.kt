package top.brokestar.emojiexporter.lsposed.http

import android.util.Log
import top.brokestar.emojiexporter.lsposed.QqRuntime
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject

abstract class BaseHandler(private val secretToken: String) : HttpHandler {
    companion object { private const val TAG = "BaseHandler" }
    final override fun handle(session: IHTTPSession, body: String?): Response {
        return try {
            if (!verifyToken(session)) {
                Log.w(TAG, "auth failed URI=" + session.uri)
                return unauthorized()
            }
            when (session.method) {
                Method.GET -> onGet(session)
                Method.POST, Method.PUT -> onPost(session, body)
                else -> methodNotAllowed()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handle failed " + session.uri, e)
            error(e.localizedMessage ?: "unknown", session.uri)
        }
    }
    open fun onGet(session: IHTTPSession): Response = methodNotAllowed()
    open fun onPost(session: IHTTPSession, body: String?): Response = methodNotAllowed()
    private fun verifyToken(session: IHTTPSession): Boolean {
        if (secretToken.isBlank()) return true
        val xAuth = session.headers["x-auth-token"]
        val auth = session.headers["authorization"]
        val queryT = session.parameters["_t"]?.firstOrNull()  // Coil 等图片加载用 query param 带 token
        val provided = when {
            !xAuth.isNullOrBlank() -> xAuth.trim()
            !auth.isNullOrBlank() -> if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else auth.trim()
            !queryT.isNullOrBlank() -> queryT.trim()
            else -> null
        } ?: return false
        return constantTimeEquals(provided, secretToken)
    }
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
    protected fun requireHookReady(): Response? {
        if (QqRuntime.classLoader == null || QqRuntime.appContext == null) {
            return error("Hook not ready, retry", null)
        }
        return null
    }
    protected fun parseJson(body: String?): JSONObject = if (body.isNullOrBlank()) JSONObject() else JSONObject(body)
    protected fun JSONObject.optStringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
    protected fun okRaw(rawJson: String): Response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, ServerCommon.MIME_JSON, rawJson)
    protected fun okBytes(bytes: ByteArray, mime: String = "image/png"): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
    protected fun empty(): Response = okRaw(JSONObject().apply { put("status", "empty") }.toString())
    protected fun ok(name: String, value: Any?): Response {
        val body = JSONObject().apply { if (value == null) put(name, JSONObject.NULL) else put(name, value) }.toString()
        return okRaw(body)
    }
    protected fun badRequest(msg: String): Response = jsonStatus(Response.Status.BAD_REQUEST, "error", msg)
    protected fun unauthorized(): Response = jsonStatus(Response.Status.UNAUTHORIZED, "unauthorized", "Invalid or missing token")
    protected fun methodNotAllowed(): Response = jsonStatus(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed", null)
    protected fun notFound(msg: String): Response = jsonStatus(Response.Status.NOT_FOUND, "not_found", msg)
    protected fun error(msg: String, uri: String?): Response {
        val body = JSONObject().apply { put("status", "error"); put("message", msg); if (uri != null) put("uri", uri) }.toString()
        return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, ServerCommon.MIME_JSON, body)
    }
    private fun jsonStatus(status: Response.Status, sv: String, msg: String?): Response {
        val body = JSONObject().apply { put("status", sv); if (msg != null) put("message", msg) }.toString()
        return NanoHTTPD.newFixedLengthResponse(status, ServerCommon.MIME_JSON, body)
    }
}
