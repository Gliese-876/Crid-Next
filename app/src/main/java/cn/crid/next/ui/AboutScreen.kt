package cn.crid.next.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cn.crid.next.R

@Composable
internal fun AboutSettingsEntry(text: UiText, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("about_entry"),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.appSurface(AppSurfaceRole.Raised),
        contentColor = MaterialTheme.colorScheme.appOnSurface(AppSurfaceRole.Raised)) {
        Row(Modifier.padding(20.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(alignByVisualCenter(Modifier.size(48.dp)).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_launcher_monochrome), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp).testTag("about_entry_icon"))
            }
            Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VisualCenterText(text.t("关于", "About", "關於"), style = MaterialTheme.typography.titleLarge)
                VisualCenterText("Crid Next", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppGlyph("next", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = alignByVisualCenter())
        }
    }
}

@Composable
internal fun AboutDialog(text: UiText, origin: ModalOrigin? = null, onDismiss: () -> Unit) {
    AnimatedAppDialog(onDismissRequest = onDismiss, fullScreen = true, origin = origin, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { motion ->
        AboutScreen(text, motion.dismiss)
    }
}

/** A separate destination keeps the introduction clear of the application's navigation. */
@Composable
internal fun AboutScreen(text: UiText, onBack: () -> Unit) {
    MatchDialogSystemBars()
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    val licenseOrigin = rememberModalOrigin()
    val noticesOrigin = rememberModalOrigin()
    var documentOrigin by remember { mutableStateOf<ModalOrigin?>(null) }
    var linkError by remember { mutableStateOf(false) }
    fun openLink(url: String) {
        linkError = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
    }

    Surface(Modifier.fillMaxSize().testTag("about_screen"), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally) {
            AboutHeader(text.t("关于", "About", "關於"), text, onBack, "about_back")
            LazyColumn(
                modifier = Modifier.weight(1f).widthIn(max = 760.dp).fillMaxWidth().testTag("about_content"),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AboutIllustration()
                        Text("Crid Next", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                            letterSpacing = (-.4).sp, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                        Text(text.t("把每一周，安排得刚刚好", "A little clarity for every week", "把每一週，安排得剛剛好"),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            VisualCenterText(text.t("版本 $version", "Version $version", "版本 $version"), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).centerVisualText())
                        }
                    }
                }
                item {
                    AboutSection(text.t("作者", "Author", "作者")) {
                        AboutRow("author", aboutPersonLabel("Gliese-876", "Gliese-876", text),
                            text.t("开发与维护", "Development and maintenance", "開發與維護"), "open",
                            onClick = { openLink(AboutProjectLinks.AUTHOR) }, modifier = Modifier.testTag("about_author"),
                            avatarResource = R.drawable.avatar_gliese_876)
                    }
                }
                item {
                    AboutSection(text.t("项目主页", "Project", "專案首頁"), role = AppSurfaceRole.Selected) {
                        AboutRow("github", "GitHub", text.t("源码与版本发布", "Source and releases", "原始碼與版本發佈"), "open",
                            onClick = { openLink(AboutProjectLinks.GITHUB) }, modifier = Modifier.testTag("about_project_github"),
                            accent = MaterialTheme.colorScheme.onPrimaryContainer)
                        AboutDivider()
                        AboutRow("gitee", "Gitee", text.t("码云镜像", "Gitee mirror", "Gitee 鏡像"), "open",
                            onClick = { openLink(AboutProjectLinks.GITEE) }, modifier = Modifier.testTag("about_project_gitee"),
                            accent = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (aboutAcknowledgements.isNotEmpty()) item {
                    AboutSection(text.t("特别鸣谢", "Special thanks", "特別鳴謝"), Modifier.testTag("about_testing_thanks")) {
                        aboutAcknowledgements.forEachIndexed { index, person ->
                            if (index > 0) AboutDivider()
                            key(person.profileUrl) {
                                AboutRow("author", person.displayLabel(text), person.contribution(text), "open",
                                    onClick = { openLink(person.profileUrl) },
                                    modifier = Modifier.testTag("about_contributor_${person.username.ifBlank { person.name }}"),
                                    avatarResource = person.avatarResource)
                            }
                        }
                    }
                }
                item {
                    AboutSection(text.t("项目沿革与许可", "History and licenses", "專案沿革與授權")) {
                        AboutRow("origin", text.t("Crid（已归档）", "Crid (archived)", "Crid（已封存）"),
                            text.t("最初的课格项目", "The original Crid project", "最初的課格專案"), "open",
                            onClick = { openLink(AboutProjectLinks.ORIGINAL_CRID) }, modifier = Modifier.testTag("about_original_project"))
                        AboutDivider()
                        AboutRow("code", text.t("MIT 开源协议", "MIT License", "MIT 開源協議"),
                            text.t("本项目原创内容", "Original project content", "本專案原創內容"), "next",
                            onClick = { licenseOrigin.capture(); documentOrigin = licenseOrigin; document = "MIT.txt" },
                            modifier = Modifier.testTag("about_license").modalOrigin(licenseOrigin))
                        AboutDivider()
                        AboutRow("thanks", text.t("第三方许可", "Third-party licenses", "第三方授權"),
                            text.t("依赖与素材", "Dependencies and assets", "相依元件與素材"), "next",
                            onClick = { noticesOrigin.capture(); documentOrigin = noticesOrigin; document = "THIRD_PARTY_NOTICES.txt" },
                            modifier = Modifier.testTag("about_notices").modalOrigin(noticesOrigin))
                    }
                }
                if (linkError) item {
                    Text(text.t("暂时无法打开链接", "This link could not be opened.", "暫時無法開啟連結"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Text("© 2026 Crid Next contributors", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    document?.let { filename ->
        val title = if (filename == "MIT.txt") text.t("MIT 开源协议", "MIT License", "MIT 開源協議")
        else text.t("第三方许可", "Third-party licenses", "第三方授權")
        AboutDocumentDialog(filename, title, text, documentOrigin) { document = null }
    }
}

@Composable
private fun AboutSection(title: String, modifier: Modifier = Modifier, role: AppSurfaceRole = AppSurfaceRole.Raised,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.appSurface(role),
            contentColor = MaterialTheme.colorScheme.appOnSurface(role)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}

@Composable
private fun AboutHeader(title: String, text: UiText, onBack: () -> Unit, tag: String) {
    Row(Modifier.widthIn(max = 808.dp).fillMaxWidth().padding(start = 10.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(alignByVisualCenter()) {
            GlyphAction("back", text.t("返回", "Back", "返回"), onBack, modifier = Modifier.testTag(tag))
        }
        VisualCenterText(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = alignByVisualCenter(Modifier.weight(1f)).semantics { heading() })
    }
}

@Composable
private fun AboutIllustration() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(156.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val unitScale = minOf(size.width / 320f, size.height / 180f)
            withTransform({
                translate((size.width - 320f * unitScale) / 2f, (size.height - 180f * unitScale) / 2f)
                scale(unitScale, unitScale, Offset.Zero)
            }) {
                // Small, quiet paper shapes frame the app icon without competing with it.
                drawRoundRect(colors.tertiaryContainer.copy(alpha = .45f), Offset(43f, 67f), Size(57f, 83f), CornerRadius(12f))
                drawLine(colors.onTertiaryContainer.copy(alpha = .18f), Offset(60f, 85f), Offset(60f, 132f), 3f, StrokeCap.Round)
                drawLine(colors.onTertiaryContainer.copy(alpha = .18f), Offset(71f, 85f), Offset(71f, 120f), 3f, StrokeCap.Round)
                drawRoundRect(colors.surfaceContainerHigh, Offset(215f, 35f), Size(63f, 85f), CornerRadius(10f))
                drawRoundRect(colors.secondaryContainer.copy(alpha = .65f), Offset(215f, 35f), Size(63f, 23f), CornerRadius(10f))
                for (row in 0..2) for (column in 0..2) {
                    drawRoundRect(colors.outlineVariant.copy(alpha = .35f), Offset(225f + column * 15f, 68f + row * 14f), Size(9f, 8f), CornerRadius(2f))
                }
            }
        }
        Surface(Modifier.size(128.dp), shape = RoundedCornerShape(32.dp), color = colors.surfaceContainerLow) {
            Box(contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.requiredSize(156.dp).testTag("about_app_icon"))
            }
        }
    }
}

@Composable
private fun AboutRow(glyph: String, title: String, subtitle: String, trailing: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, @DrawableRes avatarResource: Int? = null, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(alignByVisualCenter(Modifier.size(40.dp)), contentAlignment = Alignment.Center) {
                if (avatarResource != null) {
                    Image(painterResource(avatarResource), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                } else {
                    AboutGlyph(glyph, accent, Modifier.size(28.dp))
                }
            }
            Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                VisualCenterText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) VisualCenterText(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppGlyph(trailing, tint = accent, modifier = alignByVisualCenter(Modifier.size(20.dp)))
        }
    }
}

@Composable
private fun AboutGlyph(glyph: String, tint: Color, modifier: Modifier) {
    val brandIcon = when (glyph) {
        "github" -> R.drawable.ic_github
        "gitee" -> R.drawable.ic_gitee
        else -> null
    }
    if (brandIcon != null) {
        Icon(painterResource(brandIcon), contentDescription = null, tint = tint, modifier = modifier)
        return
    }
    Canvas(modifier) {
        val s = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val stroke = Stroke(1.7f * s)
        fun line(x: Float, y: Float, ex: Float, ey: Float) = drawLine(tint, p(x, y), p(ex, ey), 1.7f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        when (glyph) {
            "author" -> {
                drawCircle(tint, 4 * s, p(12f, 7f), style = stroke)
                drawArc(tint, 180f, 180f, false, p(4f, 13f), Size(16 * s, 14 * s), style = stroke)
            }
            "origin" -> {
                drawCircle(tint, 3.5f * s, p(7f, 12f), style = stroke)
                drawCircle(tint, 3.5f * s, p(18f, 5f), style = stroke)
                drawCircle(tint, 3.5f * s, p(18f, 19f), style = stroke)
                line(10f, 10f, 15f, 7f); line(10f, 14f, 15f, 17f)
            }
            "code" -> {
                line(7f, 6f, 2f, 12f); line(2f, 12f, 7f, 18f)
                line(17f, 6f, 22f, 12f); line(22f, 12f, 17f, 18f)
                line(14f, 3f, 10f, 21f)
            }
            else -> {
                line(12f, 2f, 12f, 8f); line(12f, 16f, 12f, 22f)
                line(2f, 12f, 8f, 12f); line(16f, 12f, 22f, 12f)
                line(5f, 5f, 8f, 8f); line(16f, 16f, 19f, 19f)
                line(5f, 19f, 8f, 16f); line(16f, 8f, 19f, 5f)
            }
        }
    }
}

@Composable
private fun AboutDocumentDialog(filename: String, title: String, text: UiText, origin: ModalOrigin?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val body = remember(filename, context, text) {
        runCatching { context.assets.open("licenses/$filename").bufferedReader().use { it.readText() } }
            .getOrElse { text.t("暂时无法读取此文件。", "This file could not be read.", "暫時無法讀取此檔案。") }
    }
    AnimatedAppDialog(onDismissRequest = onDismiss, fullScreen = true, origin = origin, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { motion ->
        MatchDialogSystemBars()
        Surface(Modifier.fillMaxSize().testTag("about_document"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally) {
                AboutHeader(title, text, motion.dismiss, "about_document_back")
                LazyColumn(Modifier.weight(1f).widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (filename == "MIT.txt") item {
                        Surface(modifier = Modifier.fillMaxWidth().testTag("about_license_scope_card"),
                            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AppGlyph("info", tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(28.dp).testTag("about_license_scope_info"))
                                    Text(text.t("适用范围", "Scope", "適用範圍"), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                                }
                                Text(text.t(
                                    "MIT 适用于本项目有权许可的原创代码、文档与资源。第三方组件和素材另依其许可及权利声明。",
                                    "MIT covers original code, documentation and assets this project has the right to license. Third-party components and assets retain their own licenses and rights notices.",
                                    "MIT 適用於本專案有權授權的原創程式碼、文件與資源。第三方元件與素材另依其授權及權利聲明。",
                                ), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("about_license_scope"))
                            }
                        }
                    }
                    item {
                        SelectionContainer {
                            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("about_license_text"))
                        }
                    }
                }
            }
        }
    }
}
