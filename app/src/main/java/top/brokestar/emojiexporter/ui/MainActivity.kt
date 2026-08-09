package top.brokestar.emojiexporter.ui

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import top.brokestar.emojiexporter.R
import top.brokestar.emojiexporter.data.QqMallApi
import com.google.android.material.tabs.TabLayoutMediator
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var tvUin: TextView
    var currentUin: String? = null

    private companion object { const val TAG = "EmojiExporter/Main" }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        tvUin = findViewById(R.id.tvUin)
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager)
        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabs)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int) = if (position == 0) InstalledPacksFragment() else SearchFragment()
        }
        TabLayoutMediator(tabs, pager) { tab, pos -> tab.text = if (pos == 0) "已添加" else "搜索商城" }.attach()
        findViewById<android.view.View>(R.id.btnRefresh).setOnClickListener { refreshUin() }
        Shell.getShell { refreshUin() }
    }

    private fun refreshUin() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 优先走 HTTP server（QQ 进程内的 HookHttpServer）
            try {
                val ticket = QqMallApi.getTicket(this@MainActivity, "gxh.vip.qq.com")
                val httpUin = ticket?.optString("uin")?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
                if (httpUin != null) {
                    Log.i(TAG, "uin via http: $httpUin")
                    withContext(Dispatchers.Main) { currentUin = httpUin; tvUin.text = httpUin }
                    return@launch
                }
            } catch (e: Throwable) { Log.w(TAG, "http uin failed", e) }

            // 回退：root 读 databases 文件名
            val hasRoot = Shell.isAppGrantedRoot() == true
            val uins = if (hasRoot) top.brokestar.emojiexporter.data.QQUinResolver.listUins() else emptyList()
            withContext(Dispatchers.Main) {
                if (!hasRoot) { tvUin.text = "未授权 Root"; Toast.makeText(this@MainActivity, "请确保 QQ 在后台运行并已登录", Toast.LENGTH_LONG).show(); return@withContext }
                if (uins.isEmpty()) { tvUin.text = "未检测到 QQ 账号"; return@withContext }
                currentUin = uins.first()
                tvUin.text = currentUin
            }
        }
    }
}
