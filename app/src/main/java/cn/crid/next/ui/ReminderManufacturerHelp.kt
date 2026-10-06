package cn.crid.next.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

internal enum class ReminderManufacturer {
    XIAOMI, HUAWEI, HONOR, VIVO,
}

/** Match the manufacturer field, not model names or substrings from another brand. */
internal fun reminderManufacturer(manufacturer: String): ReminderManufacturer? =
    when (manufacturer.trim().lowercase(Locale.ROOT)) {
        "xiaomi", "redmi" -> ReminderManufacturer.XIAOMI
        "huawei" -> ReminderManufacturer.HUAWEI
        "honor" -> ReminderManufacturer.HONOR
        "vivo", "iqoo" -> ReminderManufacturer.VIVO
        else -> null
    }

internal data class ReminderManufacturerGuide(
    val title: String,
    val steps: List<String>,
    val officialHelpUrl: String,
    val openLabel: String,
    val openFailedLabel: String,
)

internal fun reminderManufacturerGuide(manufacturer: String, text: UiText): ReminderManufacturerGuide? {
    val brand = reminderManufacturer(manufacturer) ?: return null
    val title: String
    val steps: List<String>
    val url: String
    when (brand) {
        ReminderManufacturer.XIAOMI -> {
            title = text.t("小米 / Redmi 设备设置", "Xiaomi / Redmi settings", "小米 / Redmi 裝置設定")
            steps = listOf(text.t(
                "设置 → 应用 → 权限 → 后台自启动，允许 Crid Next",
                "Settings → Apps → Permissions → Background autostart, then allow Crid Next",
                "設定 → 應用程式 → 權限 → 背景自動啟動，允許 Crid Next",
            ))
            url = "https://www.mi.com/global/support/faq/details/KA-497677/"
        }
        ReminderManufacturer.HUAWEI -> {
            title = text.t("华为设备设置", "Huawei settings", "華為裝置設定")
            steps = listOf(
                text.t("在系统设置中搜索「应用启动管理」，找到 Crid Next",
                    "Search Settings for App launch and select Crid Next",
                    "在系統設定中搜尋「應用程式啟動管理」，找到 Crid Next"),
                text.t("关闭自动管理，打开允许自启动和允许后台活动",
                    "Turn off Manage automatically, then allow Auto-launch and Run in background",
                    "關閉自動管理，開啟允許自動啟動和允許背景活動"),
            )
            url = "https://consumer.huawei.com/cn/support/content/zh-cn15872069/"
        }
        ReminderManufacturer.HONOR -> {
            title = text.t("荣耀设备设置", "Honor settings", "榮耀裝置設定")
            steps = listOf(
                text.t("设置 → 应用 → 应用启动管理 → Crid Next",
                    "Settings → Apps → App launch → Crid Next",
                    "設定 → 應用程式 → 應用程式啟動管理 → Crid Next"),
                text.t("关闭自动管理，打开允许自启动和允许后台活动",
                    "Turn off Manage automatically, then allow Auto-launch and Run in background",
                    "關閉自動管理，開啟允許自動啟動和允許背景活動"),
            )
            url = "https://www.honor.com/cn/support/content/zh-cn00685514/"
        }
        ReminderManufacturer.VIVO -> {
            title = text.t("vivo / iQOO 设备设置", "vivo / iQOO settings", "vivo / iQOO 裝置設定")
            steps = listOf(
                text.t("设置 → 应用与权限 → 权限管理 → 自启动，允许 Crid Next",
                    "Settings → Apps & permissions → Permission management → Autostart, then allow Crid Next",
                    "設定 → 應用程式與權限 → 權限管理 → 自動啟動，允許 Crid Next"),
                text.t("电池 → 后台耗电管理 → Crid Next，允许后台高耗电",
                    "Battery → Background power consumption management → Crid Next, then allow high background power usage",
                    "電池 → 背景耗電管理 → Crid Next，允許背景高耗電"),
            )
            url = "https://kefu.vivo.com.cn/robot/imgmsgData/3dfcdb616d0542b4baa4e9ead6d75c5e/index_1.html"
        }
    }
    return ReminderManufacturerGuide(
        title = title,
        steps = steps,
        officialHelpUrl = url,
        openLabel = text.t("在浏览器中查看官方说明", "View official help in browser", "在瀏覽器中查看官方說明"),
        openFailedLabel = text.t("无法打开浏览器", "Could not open a browser", "無法開啟瀏覽器"),
    )
}

@Composable
internal fun ReminderManufacturerHelp(text: UiText) {
    val guide = reminderManufacturerGuide(Build.MANUFACTURER, text) ?: return
    val context = LocalContext.current
    var openFailed by remember(guide.officialHelpUrl) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("reminder_manufacturer_help"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(guide.title, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        guide.steps.forEach { step ->
            Text(step, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = {
            openFailed = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guide.officialHelpUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isFailure
        }, modifier = Modifier.testTag("reminder_manufacturer_official_help")) {
            Text(guide.openLabel)
            Spacer(Modifier.width(8.dp))
            AppGlyph("open", modifier = Modifier.size(18.dp))
        }
        if (openFailed) Text(guide.openFailedLabel, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("reminder_manufacturer_help_error")
                .semantics { liveRegion = LiveRegionMode.Polite })
    }
}
