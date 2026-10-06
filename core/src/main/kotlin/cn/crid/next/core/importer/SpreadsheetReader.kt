package cn.crid.next.core.importer

import jxl.Workbook
import jxl.WorkbookSettings
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal data class TableCell(val row: Int, val column: Int, val text: String, val columnSpan: Int = 1, val blocks: List<String> = emptyList())
internal data class Table(val name: String, val cells: List<TableCell>, val sourceSemester: String? = null) {
    val rows: Map<Int, List<TableCell>> = cells.groupBy { it.row }
}

/** Reads data only: no formula evaluation, network requests, macros or external entities. */
internal object SpreadsheetReader {
    const val MAX_FILE_BYTES = 15 * 1024 * 1024
    private const val MAX_CELLS = 200_000
    private const val MAX_ROWS = 4096
    private const val MAX_COLUMNS = 128
    private val biffSignature = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val htmlSignature = Regex("<(?:!DOCTYPE\\s+html|html|table)\\b", RegexOption.IGNORE_CASE)
    private val semesterHeading = Regex("[（(]?\\s*\\d{4}\\s*[-－–—]\\s*\\d{4}\\s*学年\\s*(?:春季|秋季|夏季|冬季)学期\\s*[）)]?")
    private val recordBreak = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val worksheetPath = Regex("xl/worksheets/sheet\\d+\\.xml")
    private val cellAddress = Regex("([A-Z]+)(\\d+)")

    fun read(bytes: ByteArray): List<Table> {
        require(bytes.isNotEmpty()) { "文件为空，请重新选择。" }
        require(bytes.size <= MAX_FILE_BYTES) { "文件过大，请选择不超过 15 MB 的课表文件。" }
        return when {
            bytes.size >= biffSignature.size && biffSignature.indices.all { bytes[it] == biffSignature[it] } -> biff(bytes)
            bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> xlsx(bytes)
            String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1).contains(htmlSignature) -> html(bytes)
            else -> throw IllegalArgumentException("尚不支持此文件格式。请选择 XLS、XLSX、HTML 课表或 Crid Next 数据文件。")
        }
    }

    private fun biff(bytes: ByteArray): List<Table> {
        val settings = WorkbookSettings().apply { setSuppressWarnings(true); encoding = "GB18030" }
        val workbook = Workbook.getWorkbook(ByteArrayInputStream(bytes), settings)
        try {
            require(workbook.numberOfSheets <= 64) { "工作表数量过多。" }
            return workbook.sheets.map { sheet ->
                require(sheet.rows <= MAX_ROWS && sheet.columns <= MAX_COLUMNS && sheet.rows.toLong() * sheet.columns <= MAX_CELLS) { "工作表范围过大。" }
                val merged = sheet.mergedCells.associateBy { it.topLeft.row to it.topLeft.column }
                Table(sheet.name, (0 until sheet.rows).flatMap { row ->
                    (0 until sheet.columns).mapNotNull { col ->
                        val text = sheet.getCell(col, row).contents.trim()
                        if (text.isEmpty()) null else TableCell(row, col, text, merged[row to col]?.let { it.bottomRight.column - it.topLeft.column + 1 } ?: 1)
                    }
                })
            }
        } finally { workbook.close() }
    }

    private fun html(bytes: ByteArray): List<Table> {
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        document.select("script, style, input, button, .choice").remove()
        return document.select("table").filter { it.parents().none { p -> p.tagName() == "table" } }.mapIndexed { index, element ->
            val occupied = mutableSetOf<Pair<Int, Int>>()
            val cells = mutableListOf<TableCell>()
            val rows = element.select("tr").filter { it.closest("table") == element }
            require(rows.size <= MAX_ROWS) { "表格行数过多。" }
            rows.forEachIndexed { rowIndex, row ->
                var col = 0
                row.children().filter { it.tagName() == "td" || it.tagName() == "th" }.forEach { cell ->
                    while (rowIndex to col in occupied) col++
                    val width = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val height = cell.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                    require(col + width <= MAX_COLUMNS && rowIndex + height <= MAX_ROWS) { "表格跨度过大。" }
                    val explicit = cell.select(".xkinfo > div")
                    val blocks = if (explicit.isNotEmpty()) explicit.map(::recordText) else emptyList()
                    cells += TableCell(rowIndex, col, cellText(cell), width, blocks)
                    for (r in rowIndex until rowIndex + height) for (c in col until col + width) occupied += r to c
                    require(occupied.size <= MAX_CELLS) { "表格范围过大。" }
                    col += width
                }
            }
            Table("表格 ${index + 1}", cells, precedingSemester(element))
        }
    }

    /** Read an explicit heading before this table; never infer dates from hidden form fields. */
    private fun precedingSemester(table: Element): String? =
        generateSequence<Element>(table.previousElementSibling()) { it.previousElementSibling() }
            .takeWhile { it.tagName() != "table" && it.select("table").isEmpty() }
            .flatMap { it.getAllElements().asSequence() }
            .filter(::visibleHeading)
            .map { it.ownText().trim() }
            .firstOrNull { semesterHeading.matches(it) }

    private val nonHeadingTags = setOf("select", "option", "optgroup", "textarea", "input", "button", "output",
        "datalist", "meter", "progress", "template", "script", "style", "noscript")
    private val hiddenHeadingStyle = Regex("(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*(?:hidden|collapse))\\s*(?:!\\s*important\\s*)?(?=;|$)", RegexOption.IGNORE_CASE)

    /** Hidden ancestors and form choices are not document headings, including selected options. */
    private fun visibleHeading(element: Element): Boolean =
        generateSequence<Element>(element) { it.parent() }.none { ancestor ->
            ancestor.tagName() in nonHeadingTags || ancestor.hasAttr("hidden") ||
                ancestor.attr("aria-hidden").trim().equals("true", ignoreCase = true) ||
                hiddenHeadingStyle.containsMatchIn(ancestor.attr("style"))
        }

    private fun cellText(element: Element): String = element.clone().also { clone ->
        clone.select("br").forEach { it.before("\n") }
        clone.select("div, p").forEach { it.appendText("\n") }
    }.wholeText().replace('\u00a0', ' ').lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")

    // A blank field between two BR elements is meaningful (e.g. a missing teacher).
    private fun recordText(element: Element): String = element.html().split(recordBreak)
        .joinToString("\n") { Jsoup.parseBodyFragment(it).text().trim() }

    private fun xlsx(bytes: ByteArray): List<Table> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entryCount <= 256) { "文件包含过多压缩项目。" }
                val needed = entry.name == "xl/workbook.xml" || entry.name == "xl/sharedStrings.xml" || worksheetPath.matches(entry.name)
                val out = if (needed) ByteArrayOutputStream() else null
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 64 * 1024 * 1024) { "文件解压后过大。" }
                    out?.write(buffer, 0, read)
                }
                if (out != null && out.size() > 0) entries[entry.name] = out.toByteArray()
                zip.closeEntry()
            }
        }
        require("xl/workbook.xml" in entries) { "这不是有效的 XLSX 工作簿。" }
        val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
            val nodes = document(xml).getElementsByTagName("si")
            require(nodes.length <= MAX_CELLS) { "文件文本项目过多。" }
            (0 until nodes.length).map { index ->
                val strings = (nodes.item(index) as org.w3c.dom.Element).getElementsByTagName("t")
                (0 until strings.length).joinToString("") { strings.item(it).textContent }
            }
        }.orEmpty()
        val sheets = entries.filterKeys { worksheetPath.matches(it) }
        require(sheets.isNotEmpty()) { "工作簿中没有可读取的工作表。" }
        return sheets.map { (name, xml) ->
            val doc = document(xml)
            val cellNodes = doc.getElementsByTagName("c")
            require(cellNodes.length <= MAX_CELLS) { "工作表范围过大。" }
            Table(name.substringAfterLast('/'), (0 until cellNodes.length).mapNotNull { index ->
                val node = cellNodes.item(index) as org.w3c.dom.Element
                val address = cellAddress.matchEntire(node.getAttribute("r")) ?: return@mapNotNull null
                val col = address.groupValues[1].fold(0) { acc, char -> acc * 26 + (char - 'A' + 1) } - 1
                val row = address.groupValues[2].toInt() - 1
                require(row in 0 until MAX_ROWS && col in 0 until MAX_COLUMNS) { "工作表范围过大。" }
                require(node.getElementsByTagName("f").length == 0) { "课表含公式，请先在表格软件中将公式转换为值。" }
                val value = node.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                val text = when (node.getAttribute("t")) {
                    "s" -> shared.getOrNull(value.toIntOrNull() ?: -1) ?: throw IllegalArgumentException("工作簿文字索引损坏。")
                    "inlineStr" -> node.getElementsByTagName("t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent } }
                    else -> value
                }
                if (text.isBlank()) null else TableCell(row, col, text.trim())
            })
        }
    }

    private fun document(bytes: ByteArray): Document {
        val xml = bytes.toString(Charsets.UTF_8)
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "文件包含不支持的 XML 声明。" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("文件包含外部引用。") }
        }.parse(ByteArrayInputStream(bytes))
    }
}
