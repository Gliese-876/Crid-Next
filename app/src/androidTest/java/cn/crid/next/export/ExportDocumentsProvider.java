package cn.crid.next.export;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Minimal real SAF provider, installed exclusively in the instrumentation APK. */
public final class ExportDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "cn.crid.next.export.tests.documents";
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    private File directory() {
        File base = getContext().getExternalFilesDir(null);
        if (base == null) base = getContext().getCacheDir();
        File root = new File(base, "saf-export-provider");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Test directory unavailable");
        return root;
    }

    private File file(String id) throws FileNotFoundException {
        if ("root".equals(id)) return directory();
        if (!id.startsWith("root:") || id.substring(5).contains("/") || id.substring(5).contains("\\")
            || id.substring(5).equals("..")) throw new FileNotFoundException("Invalid test document");
        return new File(directory(), id.substring(5));
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES
        };
        MatrixCursor result = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : columns) {
            switch (column) {
                case Root.COLUMN_ROOT_ID: case Root.COLUMN_DOCUMENT_ID: row.add(column, "root"); break;
                case Root.COLUMN_TITLE: row.add(column, "Crid export instrumentation"); break;
                case Root.COLUMN_FLAGS: row.add(column, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD); break;
                case Root.COLUMN_AVAILABLE_BYTES: row.add(column, directory().getUsableSpace()); break;
                default: row.add(column, null);
            }
        }
        return result;
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        File target = file(id);
        if (!target.exists()) throw new FileNotFoundException(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(column, id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(column, target.getName()); break;
                case Document.COLUMN_MIME_TYPE: row.add(column, target.isDirectory() ? Document.MIME_TYPE_DIR
                    : target.getName().endsWith(".pdf") ? "application/pdf" : target.getName().endsWith(".png") ? "image/png" : "application/octet-stream"); break;
                case Document.COLUMN_FLAGS: row.add(column, target.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE
                    : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE); break;
                case Document.COLUMN_SIZE: row.add(column, target.length()); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(column, target.lastModified()); break;
                default: row.add(column, null);
            }
        }
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addDocument(result, id);
        return result;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException(parent);
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] files = directory().listFiles();
        if (files != null) for (File child : files) addDocument(result, "root:" + child.getName());
        return result;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        return "root".equals(parent) && child.startsWith("root:") && !child.substring(5).contains("/") && !child.substring(5).contains("\\");
    }

    @Override public String createDocument(String parent, String mimeType, String displayName) throws FileNotFoundException {
        if (!"root".equals(parent) || Document.MIME_TYPE_DIR.equals(mimeType)) throw new FileNotFoundException(parent);
        String name = displayName.replace('/', '_').replace('\\', '_');
        File result = new File(directory(), name);
        int suffix = 1;
        while (result.exists()) result = new File(directory(), (suffix++) + "-" + name);
        try {
            if (!result.createNewFile()) throw new IOException("Creation failed");
        } catch (IOException failure) { throw new FileNotFoundException("Cannot create test document"); }
        return "root:" + result.getName();
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if ("root".equals(id) || !file(id).delete()) throw new FileNotFoundException("Cannot delete test document");
    }
}
