package cn.crid.next.ui

internal data class AboutAcknowledgement(
    val name: String,
    val profileUrl: String,
    val username: String = "",
    val contributionZhCn: String = "",
    val contributionEn: String = "",
    val contributionZhTw: String = "",
) {
    val displayLabel: String
        get() = aboutPersonLabel(name, username)

    fun contribution(text: UiText): String = text.t(contributionZhCn, contributionEn, contributionZhTw)
}

internal fun aboutPersonLabel(name: String, username: String): String = when {
    username.isBlank() -> name
    else -> "${name.ifBlank { username }}(@$username)"
}

// Add one entry per person; the About page renders each entry with its own profile link.
internal val aboutAcknowledgements = listOf(
    AboutAcknowledgement(
        name = "方缘",
        profileUrl = "https://github.com/Fangyuanz06",
        username = "Fangyuanz06",
        contributionZhCn = "协助测试和改进",
        contributionEn = "Testing and improvements",
        contributionZhTw = "協助測試和改進",
    ),
    AboutAcknowledgement(
        name = "",
        profileUrl = "https://github.com/ChiHuchen",
        username = "ChiHuchen",
        contributionZhCn = "提供北京校区数据",
        contributionEn = "Beijing campus timetable data",
        contributionZhTw = "提供北京校區資料",
    ),
)
