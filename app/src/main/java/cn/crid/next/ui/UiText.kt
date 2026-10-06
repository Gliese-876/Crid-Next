package cn.crid.next.ui

import cn.crid.next.core.Language
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small explicit language catalog; source data (course names, teachers) is never translated. */
class UiText(language: Language, system: Locale = Locale.getDefault()) {
    val systemSupported = system.language in setOf("zh", "en")
    private val resolved = if (language != Language.SYSTEM) language else when {
        system.language == "zh" && (system.country in setOf("TW", "HK", "MO") || system.script == "Hant") -> Language.ZH_TW
        system.language == "zh" -> Language.ZH_CN
        else -> Language.EN
    }
    val isEnglish = resolved == Language.EN
    val locale: Locale = when (resolved) { Language.ZH_CN -> Locale.SIMPLIFIED_CHINESE; Language.ZH_TW -> Locale.TRADITIONAL_CHINESE; else -> Locale.ENGLISH }
    private val dateFormat = DateTimeFormatter.ofPattern(if (isEnglish) "MMM d, EEEE" else "M月d日 EEEE", locale)
    private val weekdayFormat = DateTimeFormatter.ofPattern("EEE", locale)
    fun t(zh: String, en: String, traditional: String = ""): String = when(resolved) {
        Language.ZH_CN -> zh
        Language.ZH_TW -> traditional.ifEmpty { traditional(zh) }
        else -> en
    }
    fun date(value: LocalDate): String = value.format(dateFormat)
    fun weekday(value: LocalDate): String = value.format(weekdayFormat)
    companion object {
        // UI vocabulary conversion only; each phrase may supply an explicit Traditional override.
        private const val SIMPLIFIED = "课表学时长导进设当周选项创删编辑结终数节次间开关启用显隐览确实认传输网络连线载请错误败将仅从应顶默认视图范围复盖备份路径夹权访问处调休补账费钥语简体统随浅深无过期最后同步据已过载称师点场览签组个为与这页览态余钟颜色边框线计划记录冲突并释须择义旧替换保存见参详消单独多种类原始排版缩放分辨率像素全部预估张份完恢复挂起后台通知准支撑确精到返回重试修改新建计划地名功小组件权限已授予尚未受限打开通知提醒分钟前保存取消删除编辑今天空闲暂无课程添加方案开始结束日期星期导出文件本地网络费用密钥模型配置发送上传格式无法异常确定内容目标个数当前范围学期浏览教务系统识别解析成功失败来源预览冲突重复仍然保留信息备注地点教师学分"
        private const val TRADITIONAL = "課表學時長導進設當週選項創刪編輯結終數節次間開關啟用顯隱覽確實認傳輸網絡連線載請錯誤敗將僅從應頂默認視圖範圍覆蓋備份路徑夾權訪問處調休補賬費鑰語簡體統隨淺深無過期最後同步據已過載稱師點場覽簽組個為與這頁覽態餘鐘顏色邊框線計劃記錄衝突並釋須擇義舊替換保存見參詳消單獨多種類原始排版縮放分辨率像素全部預估張份完恢復掛起後臺通知準支撐確精到返回重試修改新建計劃地名功小組件權限已授予尚未受限打開通知提醒分鐘前保存取消刪除編輯今天空閒暫無課程添加方案開始結束日期星期導出文件本地網絡費用密鑰模型配置發送上傳格式無法異常確定內容目標個數當前範圍學期瀏覽教務系統識別解析成功失敗來源預覽衝突重複仍然保留信息備註地點教師學分"
        private val mapping = SIMPLIFIED.zip(TRADITIONAL).toMap()
        fun traditional(value: String) = buildString(value.length) {
            value.forEach { append(mapping[it] ?: it) }
        }
    }
}
