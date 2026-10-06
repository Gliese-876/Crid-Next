package cn.crid.next.ui

import android.content.res.Configuration
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A picker entry is a button, so dates and clock times never require free-form input. */
@Composable
internal fun SelectionField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: String = "calendar",
) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = modifier.semantics { role = Role.Button }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                VisualCenterText(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VisualCenterText(value, style = MaterialTheme.typography.bodyLarge)
            }
            if (glyph.isNotEmpty()) AppGlyph(glyph, modifier = alignByVisualCenter(Modifier.size(20.dp)), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Material's own date/time controls read both resources and their calendar locale from this scope. */
@Composable
private fun PickerLocale(text: UiText, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localizedConfiguration = remember(configuration, text.locale) { Configuration(configuration).apply { setLocale(text.locale) } }
    val localizedContext = remember(context, localizedConfiguration) { context.createConfigurationContext(localizedConfiguration) }
    CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides localizedConfiguration, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeDatePicker(
    title: String,
    value: LocalDate,
    text: UiText,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    min: LocalDate? = null,
    max: LocalDate? = null,
    origin: ModalOrigin? = null,
    requiredWeekday: DayOfWeek? = null,
) {
    PickerLocale(text) {
        val firstYear = min?.year ?: minOf(1900, value.year)
        val lastYear = max?.year ?: maxOf(2100, value.year)
        val selected = value.coerceIn(min ?: LocalDate.of(firstYear, 1, 1), max ?: LocalDate.of(lastYear, 12, 31))
        val selectable = remember(min, max, requiredWeekday) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = pickerDateFromUtc(utcTimeMillis)
                    return (min == null || !date.isBefore(min)) && (max == null || !date.isAfter(max)) &&
                        (requiredWeekday == null || date.dayOfWeek == requiredWeekday)
                }
                override fun isSelectableYear(year: Int) = (min == null || year >= min.year) && (max == null || year <= max.year)
            }
        }
        val selectedMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = selectedMillis.takeIf(selectable::isSelectableDate),
            initialDisplayedMonthMillis = selectedMillis,
            yearRange = firstYear..lastYear, selectableDates = selectable)
        PickerDialog(title, text, onDismiss,
            selection = { state.selectedDateMillis?.takeIf(selectable::isSelectableDate)?.let(::pickerDateFromUtc) },
            onSelect = { it?.let(onSelect) },
            enabled = state.selectedDateMillis?.let(selectable::isSelectableDate) == true,
            origin = origin, showTitle = false, minimumContentWidth = 360.dp) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The native calendar has seven 48 dp targets plus 24 dp horizontal padding.
                // Its own input mode retains locale formatting and date/range validation below that width.
                val compact = maxWidth < 360.dp
                LaunchedEffect(compact) { state.displayMode = if (compact) DisplayMode.Input else DisplayMode.Picker }
                DatePicker(state = state, title = { Text(title, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)) },
                    showModeToggle = requiredWeekday != null, modifier = Modifier.testTag("native_date_picker"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeTimePicker(
    title: String,
    value: LocalTime,
    text: UiText,
    onDismiss: () -> Unit,
    onSelect: (LocalTime) -> Unit,
    origin: ModalOrigin? = null,
) {
    // Read the preference before applying the app's language to the picker context.
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    PickerLocale(text) {
        val state = rememberTimePickerState(value.hour, value.minute, is24Hour)
        PickerDialog(title, text, onDismiss, selection = { LocalTime.of(state.hour, state.minute) }, onSelect = onSelect, origin = origin) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The 12-hour display (including AM/PM) is wider than the 256 dp clock face.
                val sidePadding = ((maxWidth - 280.dp) / 2).coerceIn(0.dp, 16.dp)
                TimePicker(state, modifier = Modifier.padding(horizontal = sidePadding).testTag("native_time_picker"),
                    layoutType = TimePickerLayoutType.Vertical)
            }
        }
    }
}

@Composable
internal fun NativeWeekPicker(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    text: UiText,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    origin: ModalOrigin? = null,
) = NativeNumberPicker(title, value, min, max, text, onDismiss, onSelect, origin,
    format = { text.t("第 $it 周", "Week $it") })

@Composable
internal fun NativeNumberPicker(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    text: UiText,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    origin: ModalOrigin? = null,
    format: (Int) -> String = Int::toString,
) {
    var selected by rememberSaveable(value, min, max) { mutableIntStateOf(value.coerceIn(min, max)) }
    PickerLocale(text) {
        PickerDialog(title, text, onDismiss, selection = { selected }, onSelect = onSelect, origin = origin) {
            NativeNumberWheel(selected, min, max, title, format, { selected = it }, Modifier.fillMaxWidth().testTag("native_number_picker"))
        }
    }
}

@Composable
internal fun NativeMonthPicker(
    title: String,
    value: YearMonth,
    min: YearMonth,
    max: YearMonth,
    text: UiText,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
    origin: ModalOrigin? = null,
) {
    val initial = value.coerceIn(min, max)
    var year by rememberSaveable(value, min, max) { mutableIntStateOf(initial.year) }
    var month by rememberSaveable(value, min, max) { mutableIntStateOf(initial.monthValue) }
    val firstMonth = if (year == min.year) min.monthValue else 1
    val lastMonth = if (year == max.year) max.monthValue else 12
    PickerLocale(text) {
        PickerDialog(title, text, onDismiss, selection = { YearMonth.of(year, month.coerceIn(firstMonth, lastMonth)) }, onSelect = onSelect, origin = origin) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("native_month_picker"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NativeNumberWheel(year, min.year, max.year, text.t("年", "Year"),
                    { text.t("${it}年", it.toString()) }, { next ->
                        year = next
                        month = month.coerceIn(if (next == min.year) min.monthValue else 1, if (next == max.year) max.monthValue else 12)
                    }, Modifier.weight(1f))
                NativeNumberWheel(month.coerceIn(firstMonth, lastMonth), firstMonth, lastMonth, text.t("月", "Month"),
                    { YearMonth.of(year, it).format(DateTimeFormatter.ofPattern(if (text.isEnglish) "MMMM" else "M月", text.locale)) },
                    { month = it }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NativeNumberWheel(value: Int, min: Int, max: Int, label: String, format: (Int) -> String,
    onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val latestChange by rememberUpdatedState(onChange)
    val labels = (min..max).map(format).toTypedArray()
    AndroidView(factory = { context ->
        NumberPicker(context).apply {
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, selected -> latestChange(selected) }
        }
    }, update = { picker ->
        // Clear stale labels before the bounds change (for example December -> a partial January).
        if (picker.minValue != min || picker.maxValue != max || picker.displayedValues?.contentEquals(labels) != true) {
            picker.displayedValues = null
            if (min > picker.maxValue) picker.maxValue = max
            picker.minValue = min
            picker.maxValue = max
            picker.displayedValues = labels
        }
        if (picker.value != value) picker.value = value
        picker.contentDescription = label
        picker.setTextColor(color)
        picker.wrapSelectorWheel = false
    }, modifier = modifier.height(180.dp))
}

@Composable
private fun <T> PickerDialog(title: String, text: UiText, onDismiss: () -> Unit, selection: () -> T, onSelect: (T) -> Unit,
    enabled: Boolean = true, origin: ModalOrigin? = null, showTitle: Boolean = true, minimumContentWidth: Dp = 0.dp, content: @Composable () -> Unit) {
    AnimatedAppDialog(onDismissRequest = onDismiss, origin = origin, properties = DialogProperties(usePlatformDefaultWidth = false)) { motion ->
        // Dialog installs its own Android composition locals; reapply the app locale inside its window.
        PickerLocale(text) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val sidePadding = if (minimumContentWidth > 0.dp) ((maxWidth - minimumContentWidth) / 2).coerceIn(0.dp, 12.dp) else 12.dp
                Surface(Modifier.padding(horizontal = sidePadding).widthIn(max = 420.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showTitle) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(24.dp))
                        content()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = motion.dismiss, modifier = alignByVisualCenter(Modifier.testTag("native_picker_cancel"))) { VisualCenterText(text.t("取消", "Cancel")) }
                            TextButton(onClick = {
                                // A native wheel can settle or receive an accessibility action while exiting.
                                // Confirm the value visible at the click, then finish the spatial return.
                                val snapshot = selection()
                                motion.finish { onSelect(snapshot) }
                            }, enabled = enabled, modifier = alignByVisualCenter(Modifier.testTag("native_picker_confirm"))) { VisualCenterText(text.t("确定", "OK")) }
                        }
                    }
                }
            }
        }
    }
}

internal fun pickerDateFromUtc(value: Long): LocalDate = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()

internal fun pickerDateLabel(value: LocalDate, text: UiText): String = value.format(
    DateTimeFormatter.ofPattern(if (text.isEnglish) "MMM d, yyyy" else "yyyy年M月d日", text.locale))

@Composable
internal fun pickerTimeLabel(value: LocalTime, text: UiText): String = value.format(
    DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a", text.locale))
