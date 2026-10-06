package cn.crid.next

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.core.content.IntentCompat
import cn.crid.next.data.AppRepository
import cn.crid.next.platform.PlatformCoordinator
import cn.crid.next.ui.CridApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    private var route by mutableStateOf("today")
    private var incomingDate by mutableStateOf<String?>(null)
    private var navigationToken by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) receive(intent)
        else {
            route = savedInstanceState.getString("route") ?: "today"
            navigationToken = savedInstanceState.getInt("navigationToken")
            incoming = savedInstanceState.getString("incoming")?.let(Uri::parse)
            incomingDate = savedInstanceState.getString("incomingDate")
        }
        val repository = AppRepository.get(this)
        setContent { CridApp(repository, incoming, {
            incoming = null
            // A handled share must not reopen its preview after configuration recreation.
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra("route", route))
        }, route, navigationToken, incomingDate) }
        lifecycleScope.launch { runCatching { repository.refreshHolidays() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); navigationToken++; receive(intent) }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("route", route)
        outState.putInt("navigationToken", navigationToken)
        outState.putString("incoming", incoming?.toString())
        outState.putString("incomingDate", incomingDate)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); PlatformCoordinator.refresh(this) }
    private fun receive(intent: Intent) {
        route = intent.getStringExtra("route") ?: "today"
        incomingDate = intent.getStringExtra("date")
        val candidate = when(intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: intent.clipData?.getItemAt(0)?.uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        // Only local content-provider files; an external URL never initiates an upload or download.
        incoming = candidate?.takeIf { it.scheme == "content" }
    }
}
