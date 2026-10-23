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

    private static final String[] COLS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
    };

    public static List<Entry> list(Context ctx, Uri tree) throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        return query(ctx, children, tree, true);
    }

    public static List<Entry> siblings(Context ctx, Uri docUri) throws IOException {
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(docUri);
        } catch (Exception e) {
            throw new IOException("berkas ini bukan dokumen folder biasa");
        }
        int slash = docId.lastIndexOf('/');
        if (slash <= 0) {
            throw new IOException("folder induk tidak bisa ditentukan dari " + docId);
        }
        String parentId = docId.substring(0, slash);
        Uri children = DocumentsContract.buildChildDocumentsUri(docUri.getAuthority(), parentId);
        return query(ctx, children, docUri, false);
    }

    public static String displayName(Context ctx, Uri docUri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = docUri.getLastPathSegment();
        return last == null ? "scatter" : last;
    }

    private static List<Entry> query(Context ctx, Uri children, Uri base, boolean tree)
            throws IOException {
        List<Entry> out = new ArrayList<>();
        Cursor c = ctx.getContentResolver().query(children, COLS, null, null, null);
        if (c == null) {
            throw new IOException(tree
                    ? "folder tidak bisa dibaca, pilih ulang foldernya"
                    : "folder induk scatter tidak bisa dibaca");
        }
        try {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.docId = c.getString(0);
                e.name = c.getString(1) == null ? "" : c.getString(1);
                e.mime = c.getString(2) == null ? "" : c.getString(2);
                e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(e.mime);
                if (!c.isNull(3)) e.size = c.getLong(3);
                e.uri = tree
                        ? DocumentsContract.buildDocumentUriUsingTree(base, e.docId)
                        : DocumentsContract.buildDocumentUri(base.getAuthority(), e.docId);
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
