package cn.crid.next.export;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Grants one test tree from its actual owner UID, without adopting shell privileges. */
public final class ExportGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.net.Uri tree = DocumentsContract.buildTreeDocumentUri(ExportDocumentsProvider.AUTHORITY, "root");
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (getIntent().getBooleanExtra("revoke", false)) {
            revokeUriPermission("cn.crid.next", tree, flags);
        } else {
            grantUriPermission("cn.crid.next", tree, flags | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
