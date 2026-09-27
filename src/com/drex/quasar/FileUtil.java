package com.drex.quasar;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class FileUtil {

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static String formatSpeed(double mbs) {
        if (mbs < 0.05) return "0 MB/s";
        if (mbs < 10) return String.format("%.1f MB/s", mbs);
        return String.format("%.0f MB/s", mbs);
    }

    public static String getName(Context ctx, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name == null) name = "archivo";
        }
        return name;
    }

    public static long getSize(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    public static String getMime(Context ctx, Uri uri) {
        try {
            String m = ctx.getContentResolver().getType(uri);
            if (m != null) return m;
        } catch (Exception ignored) {
        }
        String ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        if (ext != null) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    public interface CopyProgress {
        void onCopy(String name, long done, long total);
    }

    public static File copyToCache(Context ctx, Uri uri, String name, CopyProgress cp) throws Exception {
        File dir = new File(ctx.getCacheDir(), "send");
        if (!dir.exists()) dir.mkdirs();
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File out = new File(dir, System.currentTimeMillis() + "_" + safe);
        ContentResolver cr = ctx.getContentResolver();
        long total = getSize(ctx, uri);
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new Exception("No se pudo leer el archivo");
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[256 * 1024];
        long done = 0;
        int r;
        try {
            while ((r = in.read(buf)) != -1) {
                os.write(buf, 0, r);
                done += r;
                if (cp != null) cp.onCopy(name, done, total);
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { os.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    public static void clearCache(Context ctx) {
        try {
            File dir = new File(ctx.getCacheDir(), "send");
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } catch (Exception ignored) {
        }
    }
}
