package top.brokestar.emojiexporter.lsposed.http

import android.content.Context
import java.security.SecureRandom

object TokenStore {
    private const val PREF = "emoji_rpc"
    private const val KEY = "auth_token"
    fun getOrCreate(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var tok = sp.getString(KEY, null)
        if (!tok.isNullOrBlank()) return tok
        tok = genToken()
        sp.edit().putString(KEY, tok).apply()
        return tok
    }
    fun get(context: Context): String? = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
    private fun genToken(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
