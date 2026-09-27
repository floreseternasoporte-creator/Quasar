package com.drex.quasar;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Destino de recepción: escribe en Descargas/Quasar.
 * API 29+: MediaStore con IS_PENDING + RandomAccessFile sobre el fd (permite seeks para chunks).
 * API 26-28: archivo directo (requiere WRITE_EXTERNAL_STORAGE).
 */
public class MediaStoreTarget implements TransferEngine.ReceiveTarget {

    private final Context ctx;
    private final String name;
    private final long size;
    private final String mime;

    private RandomAccessFile raf;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private File legacyFile;
    private long received = 0;
    private final Object lock = new Object();
    private boolean done = false;

    public MediaStoreTarget(Context ctx, String name, long size, String mime) {
        this.ctx = ctx.getApplicationContext();
        this.name = name;
        this.size = size;
        this.mime = mime == null ? "application/octet-stream" : mime;
    }

    public void create() throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName(name));
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Quasar");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            ContentResolver cr = ctx.getContentResolver();
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("No se pudo crear el archivo de destino");
            pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd == null) throw new Exception("No se pudo abrir el destino");
            java.lang.reflect.Constructor<RandomAccessFile> ctor =
                    RandomAccessFile.class.getConstructor(java.io.FileDescriptor.class);
            raf = ctor.newInstance(pfd.getFileDescriptor());
            raf.setLength(size);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "Quasar");
            if (!dir.exists()) dir.mkdirs();
            legacyFile = uniqueFile(dir, name);
            raf = new RandomAccessFile(legacyFile, "rw");
            raf.setLength(size);
        }
    }

    private String uniqueName(String base) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String[] proj = { MediaStore.Downloads.DISPLAY_NAME };
            int n = 0;
            String cand = base;
            while (true) {
                Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                        MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{ cand }, null);
                boolean exists = false;
                if (c != null) {
                    exists = c.moveToFirst();
                    c.close();
                }
                if (!exists) return cand;
                n++;
                int dot = base.lastIndexOf('.');
                cand = (dot > 0 ? base.substring(0, dot) + " (" + n + ")" + base.substring(dot)
                               : base + " (" + n + ")");
                if (n > 99) return System.currentTimeMillis() + "_" + base;
            }
        } catch (Exception e) {
            return base;
        }
    }

    private File uniqueFile(File dir, String base) {
        File f = new File(dir, base);
        int n = 0;
        while (f.exists() && n < 100) {
            n++;
            int dot = base.lastIndexOf('.');
            String cand = (dot > 0 ? base.substring(0, dot) + " (" + n + ")" + base.substring(dot)
                                  : base + " (" + n + ")");
            f = new File(dir, cand);
        }
        return f;
    }

    @Override
    public void write(long offset, byte[] data, int len) throws Exception {
        synchronized (lock) {
            if (raf == null) throw new Exception("Destino cerrado");
            raf.seek(offset);
            raf.write(data, 0, len);
            received += len;
        }
    }

    @Override
    public long received() {
        synchronized (lock) {
            return received;
        }
    }

    @Override
    public void complete() throws Exception {
        synchronized (lock) {
            if (done) return;
            done = true;
            try { if (raf != null) raf.close(); } catch (Exception ignored) {}
            raf = null;
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            pfd = null;
            if (uri != null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                try {
                    ctx.getContentResolver().update(uri, cv, null, null);
                } catch (Exception ignored) {}
            } else if (legacyFile != null) {
                try {
                    MediaScannerConnection.scanFile(ctx,
                            new String[]{ legacyFile.getAbsolutePath() }, null, null);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void abort() {
        synchronized (lock) {
            done = true;
            try { if (raf != null) raf.close(); } catch (Exception ignored) {}
            raf = null;
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            pfd = null;
            try {
                if (uri != null) ctx.getContentResolver().delete(uri, null, null);
                else if (legacyFile != null) legacyFile.delete();
            } catch (Exception ignored) {}
        }
    }
}
