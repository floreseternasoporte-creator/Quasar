package com.drex.quasar;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quasar 2.0 — explorador propio con miniaturas reales.
 * Pestañas: Fotos, Videos, Música, Documentos, Todo. Selección múltiple.
 */
public class FileBrowserActivity extends Activity {

    private static final int TAB_PHOTOS = 0, TAB_VIDEOS = 1, TAB_MUSIC = 2, TAB_DOCS = 3, TAB_ALL = 4;

    private static class MediaItem {
        Uri uri;
        String name;
        long size;
        long date;
        int kind; // 0 img, 1 video, 2 audio, 3 doc, 4 otro
    }

    private int tab = TAB_PHOTOS;
    private final List<MediaItem> items = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();
    private MediaAdapter adapter;
    private GridView grid;
    private TextView txtSelected, txtEmpty, txtLoading, btnContinue;
    private View emptyBox;
    private TextView[] filters;

    // 2.3: caché dimensionada a 1/16 de la memoria máxima. ANTES era de
    // solo 48KB y cada miniatura de 192px pesaba ~147KB: la caché NUNCA
    // retenía nada y cada scroll recargaba todas las miniaturas.
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(thumbCacheKb()) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount() / 1024;
        }
    };

    private static int thumbCacheKb() {
        long maxKb = Runtime.getRuntime().maxMemory() / 1024;
        return (int) Math.max(4 * 1024, maxKb / 16);
    }
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(3);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        grid = findViewById(R.id.grid_files);
        txtSelected = findViewById(R.id.txt_selected);
        txtEmpty = findViewById(R.id.txt_empty_files);
        txtLoading = findViewById(R.id.txt_loading);
        btnContinue = findViewById(R.id.btn_continue);
        emptyBox = findViewById(R.id.empty_box);

        // 2.2: si la galería sale vacía por acceso parcial, atajo a ajustes
        View btnSettings = findViewById(R.id.btn_empty_settings);
        Cine.pressFx(btnSettings);
        btnSettings.setOnClickListener(v -> {
            try {
                Intent i = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {}
        });

        filters = new TextView[]{
                findViewById(R.id.filter_photos), findViewById(R.id.filter_videos),
                findViewById(R.id.filter_music), findViewById(R.id.filter_docs),
                findViewById(R.id.filter_all)};
        for (int i = 0; i < filters.length; i++) {
            final int t = i;
            filters[i].setOnClickListener(v -> setTab(t));
        }

        adapter = new MediaAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> toggleSelect(pos, v));

        btnContinue.setOnClickListener(v -> {
            if (selected.isEmpty()) return;
            ArrayList<Uri> uris = new ArrayList<>();
            for (int pos : selected) uris.add(items.get(pos).uri);
            Intent r = new Intent();
            r.putParcelableArrayListExtra("uris", uris);
            setResult(RESULT_OK, r);
            finish();
            overridePendingTransition(R.anim.hold, R.anim.slide_out_down);
        });
        Cine.pressFx(btnContinue);

        setTab(TAB_PHOTOS);
    }

    private void setTab(int t) {
        tab = t;
        selected.clear();
        for (int i = 0; i < filters.length; i++) {
            boolean on = i == t;
            filters[i].setBackgroundResource(on ? R.drawable.btn_primary : R.drawable.card);
            filters[i].setTextColor(on ? 0xFFFFFFFF : 0xFF9CA3AF);
            // 2.1: el filtro activo respira con easing cinematográfico
            if (on) {
                filters[i].animate().cancel();
                filters[i].setScaleX(0.92f);
                filters[i].setScaleY(0.92f);
                filters[i].animate().scaleX(1f).scaleY(1f).setDuration(380)
                        .setInterpolator(Cine.CINEMATIC).start();
            }
        }
        // 2.1: la rejilla se funde al cambiar de filtro
        grid.animate().cancel();
        grid.animate().alpha(0f).setDuration(140).setInterpolator(Cine.EASE_OUT)
                .withEndAction(this::loadItems).start();
    }

    private void toggleSelect(int pos, View cell) {
        ImageView check = cell.findViewById(R.id.media_check);
        if (selected.contains(pos)) {
            selected.remove(pos);
            check.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(160).withEndAction(() -> {
                check.setVisibility(View.GONE);
                check.setAlpha(1f);
                check.setScaleX(1f);
                check.setScaleY(1f);
            }).start();
            cell.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        } else {
            selected.add(pos);
            check.setVisibility(View.VISIBLE);
            check.setScaleX(0.3f);
            check.setScaleY(0.3f);
            check.animate().scaleX(1f).scaleY(1f).setDuration(340)
                    .setInterpolator(Cine.ELASTIC).start();
            cell.animate().scaleX(0.94f).scaleY(0.94f).setDuration(160).start();
        }
        updateCounter();
    }

    private void updateCounter() {
        int n = selected.size();
        if (n == 0) {
            txtSelected.setText(getString(R.string.pick_files));
            txtSelected.setTextColor(0xFF9CA3AF);
            btnContinue.setAlpha(0.4f);
        } else {
            txtSelected.setText(getString(R.string.selected_count, n));
            txtSelected.setTextColor(0xFFFFFFFF);
            btnContinue.setAlpha(1f);
            // 2.1: pulso cinematográfico (acento puntual, sin rebote de juguete)
            btnContinue.animate().cancel();
            btnContinue.animate().scaleX(1.05f).scaleY(1.05f).setDuration(140)
                    .setInterpolator(Cine.EASE_OUT)
                    .withEndAction(() -> btnContinue.animate().scaleX(1f).scaleY(1f)
                            .setDuration(300).setInterpolator(Cine.CINEMATIC).start()).start();
        }
    }

    // ---------------- carga de archivos ----------------

    /** 2.1: instante de la carga actual — solo las celdas de los primeros
     * 700ms reciben entrada escalonada (las recicladas aparecen limpias). */
    private long loadGenMs = 0;

    /** 2.3: generación de carga — si el usuario cambia de pestaña rápido,
     * solo la última consulta aplica sus resultados. */
    private int loadGen = 0;

    private void loadItems() {
        txtLoading.setVisibility(View.VISIBLE);
        emptyBox.setVisibility(View.GONE);
        items.clear();
        adapter.notifyDataSetChanged();
        final int gen = ++loadGen;
        new Thread(() -> {
            List<MediaItem> found = queryTab(tab);
            runOnUiThread(() -> {
                if (gen != loadGen) return; // una carga más nueva ya ganó
                items.addAll(found);
                // 2.3: ya no se vacía la caché al cambiar de pestaña;
                // volver atrás ahora es instantáneo.
                loadGenMs = android.os.SystemClock.uptimeMillis();
                adapter.notifyDataSetChanged();
                txtLoading.setVisibility(View.GONE);
                emptyBox.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                updateCounter();
                // la rejilla regresa con fundido cinematográfico
                grid.animate().cancel();
                grid.animate().alpha(1f).setDuration(320)
                        .setInterpolator(Cine.CINEMATIC).start();
            });
        }).start();
    }

    private List<MediaItem> queryTab(int t) {
        List<MediaItem> out = new ArrayList<>();
        try {
            switch (t) {
                case TAB_PHOTOS:
                    queryMedia(out, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 0);
                    break;
                case TAB_VIDEOS:
                    queryMedia(out, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1);
                    break;
                case TAB_MUSIC:
                    queryMedia(out, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 2);
                    break;
                case TAB_DOCS:
                    queryDocs(out);
                    break;
                case TAB_ALL:
                    queryMedia(out, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 0);
                    queryMedia(out, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1);
                    queryMedia(out, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 2);
                    queryDocs(out);
                    break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void queryMedia(List<MediaItem> out, Uri base, int kind) {
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED};
        Cursor c = null;
        try {
            // 2.2: sin LIMIT dentro del sortOrder (algunos fabricantes lo rechazan
            // y la galería quedaba vacía); el tope se aplica en Java.
            c = getContentResolver().query(base, proj, null, null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return;
            int iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            while (c.moveToNext() && out.size() < 400) {
                MediaItem m = new MediaItem();
                long id = c.getLong(iId);
                m.uri = ContentUris.withAppendedId(base, id);
                m.name = c.getString(iName);
                if (m.name == null) m.name = "archivo";
                m.size = c.getLong(iSize);
                m.date = c.getLong(iDate) * 1000L;
                m.kind = kind;
                out.add(m);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private void queryDocs(List<MediaItem> out) {
        Uri base = MediaStore.Files.getContentUri("external");
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE};
        String sel = MediaStore.MediaColumns.MIME_TYPE + " IN ("
                + "'application/pdf','application/msword',"
                + "'application/vnd.openxmlformats-officedocument.wordprocessingml.document',"
                + "'application/vnd.ms-excel',"
                + "'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',"
                + "'application/vnd.ms-powerpoint',"
                + "'application/vnd.openxmlformats-officedocument.presentationml.presentation',"
                + "'text/plain','application/zip','application/x-rar-compressed',"
                + "'application/vnd.android.package-archive','application/epub+zip')";
        Cursor c = null;
        try {
            // 2.2: sin LIMIT dentro del sortOrder; el tope se aplica en Java.
            c = getContentResolver().query(base, proj, sel, null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return;
            int iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            while (c.moveToNext() && out.size() < 400) {
                MediaItem m = new MediaItem();
                long id = c.getLong(iId);
                m.uri = ContentUris.withAppendedId(base, id);
                m.name = c.getString(iName);
                if (m.name == null) m.name = "documento";
                m.size = c.getLong(iSize);
                m.date = c.getLong(iDate) * 1000L;
                m.kind = 3;
                out.add(m);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------------- miniaturas ----------------

    private void loadThumb(MediaItem m, ImageView target, int kind) {
        String key = m.uri.toString();
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setTag(key);
        target.setImageResource(kind == 1 ? R.drawable.ic_file_video
                : kind == 2 ? R.drawable.ic_file_audio
                : kind == 3 ? R.drawable.ic_file_doc : R.drawable.ic_file_image);
        if (kind > 1) return; // audio/docs usan ícono de tipo
        thumbPool.execute(() -> {
            Bitmap b = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    // 2.3: 384px — nítido hasta xxxhdpi en celdas de 3 columnas
                    b = getContentResolver().loadThumbnail(m.uri, new Size(384, 384), null);
                } else {
                    long id = ContentUris.parseId(m.uri);
                    if (kind == 0) {
                        b = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), id,
                                MediaStore.Images.Thumbnails.MINI_KIND, null);
                    } else {
                        b = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), id,
                                MediaStore.Video.Thumbnails.MINI_KIND, null);
                    }
                }
            } catch (Exception ignored) {
            }
            final Bitmap bmp = b;
            if (bmp != null) thumbCache.put(key, bmp);
            runOnUiThread(() -> {
                if (bmp != null && key.equals(target.getTag())) {
                    target.setImageBitmap(bmp);
                    target.setAlpha(0f);
                    target.animate().alpha(1f).setDuration(220).start();
                }
            });
        });
    }

    // ---------------- adaptador ----------------

    private class MediaAdapter extends BaseAdapter {
        private final DateFormat df = DateFormat.getDateInstance(DateFormat.SHORT);

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            boolean isNew = v == null;
            if (isNew) {
                v = LayoutInflater.from(FileBrowserActivity.this)
                        .inflate(R.layout.item_media, parent, false);
                // 2.1: entrada escalonada solo justo después de cargar
                long age = android.os.SystemClock.uptimeMillis() - loadGenMs;
                if (age < 700) {
                    v.animate().cancel();
                    v.setAlpha(0f);
                    v.setScaleX(0.9f);
                    v.setScaleY(0.9f);
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setStartDelay((p % 15) * 30L).setDuration(400)
                            .setInterpolator(Cine.CINEMATIC).start();
                }
            }
            MediaItem m = items.get(p);
            ImageView thumb = v.findViewById(R.id.media_thumb);
            ImageView check = v.findViewById(R.id.media_check);
            TextView name = v.findViewById(R.id.media_name);
            TextView size = v.findViewById(R.id.media_size);

            loadThumb(m, thumb, m.kind);
            name.setText(m.name);
            String detail = FileUtil.formatSize(m.size);
            if (m.date > 0) detail += " · " + df.format(new Date(m.date));
            size.setText(detail);

            boolean sel = selected.contains(p);
            check.setVisibility(sel ? View.VISIBLE : View.GONE);
            v.setScaleX(sel ? 0.94f : 1f);
            v.setScaleY(sel ? 0.94f : 1f);
            return v;
        }
    }

    @Override
    protected void onDestroy() {
        thumbPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.slide_out_down);
    }
}
