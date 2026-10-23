package com.rizky.mtkflash;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class RomFolder {

    public static class Entry {
        public String name = "";
        public String docId = "";
        public String mime = "";
        public Uri uri;
        public long size = -1;
        public boolean dir = false;
    }

    public static List<Entry> list(Context ctx, Uri tree) throws IOException {
        List<Entry> out = new ArrayList<>();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        Cursor c = ctx.getContentResolver().query(children, cols, null, null, null);
        if (c == null) throw new IOException("folder tidak bisa dibaca, pilih ulang foldernya");
        try {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.docId = c.getString(0);
                e.name = c.getString(1) == null ? "" : c.getString(1);
                e.mime = c.getString(2) == null ? "" : c.getString(2);
                e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(e.mime);
                if (!c.isNull(3)) e.size = c.getLong(3);
                e.uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public static InputStream open(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("berkas tidak bisa dibuka");
        return in;
    }

    public static long size(Context ctx, Uri uri, long fallback) {
        if (fallback > 0) return fallback;
        ParcelFileDescriptor p = null;
        try {
            p = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (p == null) return -1;
            long s = p.getStatSize();
            return s > 0 ? s : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) {
                try {
                    p.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
