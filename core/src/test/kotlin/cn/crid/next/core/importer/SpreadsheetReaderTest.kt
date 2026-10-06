package cn.crid.next.core.importer

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpreadsheetReaderTest {
    @Test fun `xlsx retains shared and inline strings while ignoring unrelated XML`() {
        val bytes = workbook {
            entry("xl/styles.xml", "unused XML".toByteArray())
            entry("xl/sharedStrings.xml", "<sst><si><r><t>共享</t></r><r><t>文本</t></r></si></sst>".toByteArray())
            entry("xl/worksheets/sheet1.xml", """<worksheet><sheetData><row>
                <c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>内联文本</t></is></c>
                </row></sheetData></worksheet>""".toByteArray())
            entry("xl/worksheets/_rels/sheet1.xml.rels", "unused relationships".toByteArray())
        }
        val table = SpreadsheetReader.read(bytes).single()
        assertEquals(listOf(TableCell(0, 0, "共享文本"), TableCell(0, 1, "内联文本")), table.cells)
        assertEquals(mapOf(0 to table.cells), table.rows)
    }

    @Test fun `ignored zip entries still count toward the decompressed size limit`() {
        val bytes = workbook {
            putNextEntry(ZipEntry("xl/styles.xml"))
            val block = ByteArray(8192)
            repeat(64 * 1024 * 1024 / block.size) { write(block) }
            closeEntry()
        }
        val error = assertFailsWith<IllegalArgumentException> { SpreadsheetReader.read(bytes) }
        assertEquals("文件解压后过大。", error.message)
    }

    private fun workbook(contents: ZipOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.entry("xl/workbook.xml", "<workbook/>".toByteArray())
            zip.contents()
        }
    }.toByteArray()

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
