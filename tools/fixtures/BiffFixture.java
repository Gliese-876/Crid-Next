import java.io.File;
import java.util.Locale;
import jxl.CellType;
import jxl.Workbook;
import jxl.WorkbookSettings;
import jxl.write.Formula;
import jxl.write.Label;
import jxl.write.Number;
import jxl.write.WritableSheet;
import jxl.write.WritableWorkbook;

/** Fixed synthetic BIFF8 workbook, generated with the same JExcelAPI version as the app. */
class BiffFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output XLS path");
        File output = new File(args[0]);
        if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) {
            throw new IllegalStateException("Cannot create fixture directory");
        }
        WorkbookSettings settings = new WorkbookSettings();
        settings.setLocale(Locale.US);
        settings.setEncoding("GB18030");
        WritableWorkbook workbook = Workbook.createWorkbook(output, settings);
        try {
            WritableSheet sheet = workbook.createSheet("课程表", 0);
            sheet.addCell(new Label(0, 0, "2026-2027学年秋季学期"));
            sheet.mergeCells(0, 0, 6, 0);
            String[] headers = {"课程代码", "课程名称", "任课教师", "学分", "总学时", "上课班号", "上课时间地点"};
            for (int column = 0; column < headers.length; column++) {
                sheet.addCell(new Label(column, 1, headers[column]));
            }
            sheet.addCell(new Label(0, 2, "CS001"));
            sheet.addCell(new Label(1, 2, "数据结构"));
            sheet.addCell(new Label(2, 2, "张老师"));
            sheet.addCell(new Number(3, 2, 3.5));
            // JExcelAPI writes a cached numeric value of 4 for a new formula. SUM(2,2)
            // deliberately matches it; the importer reads that cache, without evaluation.
            sheet.addCell(new Formula(4, 2, "SUM(2,2)"));
            sheet.addCell(new Number(5, 2, 7));
            sheet.addCell(new Label(6, 2, "1-4周 三[2-3] 科学楼203"));
            workbook.write();
        } finally {
            workbook.close();
        }
        Workbook check = Workbook.getWorkbook(output, settings);
        try {
            if (check.getSheet(0).getCell(4, 2).getType() != CellType.NUMBER_FORMULA ||
                    !check.getSheet(0).getCell(4, 2).getContents().equals("4")) {
                throw new IllegalStateException("Expected a real BIFF formula with cached numeric value 4");
            }
        } finally {
            check.close();
        }
        System.out.println("Generated " + output + " (" + output.length() + " bytes)");
    }
}
