package xiao.bu.channel.files;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** Isolated from the API-15 entry point; call only after the relevant SDK check. */
final class ModernStorage {
    static boolean canReadSharedStorage(Context context) { // API 23
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    static void requestSharedStorage(android.app.Activity activity) { // API 23
        activity.requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 0x4642);
    }
    static File[] externalDirs(Context context) { return context.getExternalFilesDirs(null); } // API 19
    static List<BrowserEntry> documents(Context context, Uri tree) throws IOException { // API 21
        List<BrowserEntry> result = new ArrayList<>();
        String id = DocumentsContract.isDocumentUri(context, tree) ? DocumentsContract.getDocumentId(tree) : DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = context.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("目录不可读");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1), mime = cursor.getString(2);
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (dir || (mime != null && mime.startsWith("video/")) || BrowserEntry.video(name))
                    result.add(new BrowserEntry(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString(), dir, 1));
            }
        }
        return result;
    }
}
