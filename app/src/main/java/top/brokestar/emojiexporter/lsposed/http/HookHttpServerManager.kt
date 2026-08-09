package top.brokestar.emojiexporter.lsposed.http

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule

object HookHttpServerManager {
    private const val TAG = "HookHttpServerMgr"
    @Volatile private var server: HookHttpServer? = null
    @Volatile var actualPort: Int = 8080
        private set
    @Volatile var token: String = ""
        private set
    @Synchronized
    fun startIfNeeded(module: XposedModule, context: Context, preferredPort: Int = 8080) {
        if (server?.isAlive == true) { Log.i(TAG, "already running on " + actualPort); return }
        token = TokenStore.getOrCreate(context)
        var port = preferredPort
        var last: Exception? = null
        for (attempt in 0..1) {
            try {
                val s = HookHttpServer(port, token)
                s.startServer()
                server = s
                actualPort = port
                module.log(Log.INFO, TAG, "HookHttpServer started on 127.0.0.1:" + port + " token=" + token.take(6) + "...")
                Log.i(TAG, "HookHttpServer started on 127.0.0.1:" + port)
                // persist port for App discovery via shared_prefs（App 用 root shell 读取）
                context.getSharedPreferences("emoji_rpc", Context.MODE_PRIVATE).edit().putInt("http_port", port).apply()
                return
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "port " + port + " failed: " + e.message)
                port = 8081
            }
        }
        Log.e(TAG, "start failed", last)
        try { module.log(Log.ERROR, TAG, "HookHttpServer start failed: " + last?.message, last) } catch (_: Throwable) {}
    }
    fun stop() { try { server?.stopServer() } catch (_: Throwable) {}; server = null }
}
