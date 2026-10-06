package cn.crid.next.core.importer

import jxl.Workbook
import jxl.WorkbookSettings
import jxl.read.biff.CompoundFile
import org.jsoup.Jsoup
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Check fields the timetable parser deliberately ignores, before fixtures enter test APKs. */
class FixturePrivacyTest {
    private val root = sequenceOf(File(".."), File(".")).first { File(it, "tests/测试用例").isDirectory }
    private val fixtures = File(root, "tests/测试用例").listFiles()!!.filter { it.extension == "xls" }
    private val syntheticName = Regex("SYNTHETIC|示例|合成|匿名|测试", RegexOption.IGNORE_CASE)

    private fun syntheticStudentId(value: String): Boolean =
        value.matches(Regex("[0-9]{12}")) &&
            (value.startsWith("99") || value.take(4).toInt() >= 2050)

    @Test fun `visible and hidden student identities use synthetic values`() {
        assertEquals(6, fixtures.size)
        fixtures.forEach { file ->
            val bytes = file.readBytes()
            if (bytes[0] == 0xd0.toByte()) {
                val workbook = Workbook.getWorkbook(bytes.inputStream())
                try {
                    val sheet = workbook.getSheet(0)
                    val header = (0 until sheet.rows).first { row -> sheet.getRow(row).any { it.contents == "学号" } }
                    val idColumn = sheet.getRow(header).single { it.contents == "学号" }.column
                    val nameColumn = sheet.getRow(header).single { it.contents == "姓名" }.column
                    val rows = (header + 1 until sheet.rows).filter { sheet.getCell(idColumn, it).contents.isNotBlank() }
                    assertTrue(rows.isNotEmpty(), file.name)
                    rows.forEach { row ->
                        assertTrue(syntheticStudentId(sheet.getCell(idColumn, row).contents), file.name)
                        assertTrue(syntheticName.containsMatchIn(sheet.getCell(nameColumn, row).contents), file.name)
                    }
                } finally {
                    workbook.close()
                }
            } else {
                val document = Jsoup.parse(bytes.inputStream(), null, "")
                // Includes hidden inputs and hidden list columns, not just visible text.
                val ids = Regex("(?<![0-9])[0-9]{12}(?![0-9])").findAll(document.outerHtml()).map { it.value }.toList()
                assertTrue(ids.isNotEmpty(), file.name)
                assertTrue(ids.all(::syntheticStudentId), file.name)
                Regex("姓名\\s*[:：]\\s*([^\\s]+)").findAll(document.text()).forEach {
                    assertTrue(syntheticName.containsMatchIn(it.groupValues[1]), file.name)
                }
            }
        }
    }

    @Test fun `BIFF writer metadata contains only a synthetic author or the library producer`() {
        fixtures.filter { it.readBytes()[0] == 0xd0.toByte() }.forEach { file ->
            // Use JExcelAPI's OLE reader; cell APIs omit the WRITEACCESS record.
            val stream = CompoundFile(file.readBytes(), WorkbookSettings()).getStream("Workbook")
            val records = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
            var authors = 0
            while (records.remaining() >= 4) {
                val type = records.short.toInt() and 0xffff
                val size = records.short.toInt() and 0xffff
                if (type == 0 && size == 0) break
                assertTrue(size <= records.remaining(), file.name)
                val data = ByteArray(size).also(records::get)
                if (type != 0x005c) continue
                authors++
                val producer = data.toString(Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
                if (producer.matches(Regex("Java Excel API v[0-9.]+"))) continue
                assertTrue(data.size >= 3, file.name)
                val count = (data[0].toInt() and 0xff) or ((data[1].toInt() and 0xff) shl 8)
                val wide = data[2].toInt() and 1 != 0
                val length = count * if (wide) 2 else 1
                assertTrue(length <= data.size - 3, file.name)
                val author = String(data, 3, length, if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1)
                assertTrue(author.matches(Regex("示例[\\p{IsHan}]")), file.name)
                assertTrue(data.drop(3 + length).all { it == 0.toByte() || it == 32.toByte() }, file.name)
            }
            assertEquals(1, authors, file.name)
        }
    }
}
