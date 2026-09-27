package com.drex.quasar;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class HistoryStore {

    public static class Entry {
        public String name;
        public long size;
        public long time;
        public boolean sent;
        public String peer;
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "history.json");
    }

    public static synchronized void add(Context ctx, String name, long size, boolean sent, String peer) {
        try {
            List<Entry> all = load(ctx);
            Entry e = new Entry();
            e.name = name;
            e.size = size;
            e.time = System.currentTimeMillis();
            e.sent = sent;
            e.peer = peer == null ? "" : peer;
            all.add(0, e);
            while (all.size() > 100) all.remove(all.size() - 1);
            JSONArray arr = new JSONArray();
            for (Entry x : all) {
                JSONObject o = new JSONObject();
                o.put("name", x.name);
                o.put("size", x.size);
                o.put("time", x.time);
                o.put("sent", x.sent);
                o.put("peer", x.peer);
                arr.put(o);
            }
            FileOutputStream fos = new FileOutputStream(file(ctx));
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Entry> load(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            File f = file(ctx);
            if (!f.exists()) return out;
            FileInputStream fis = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int r = 0, off = 0;
            while (off < b.length && (r = fis.read(b, off, b.length - off)) != -1) off += r;
            fis.close();
            JSONArray arr = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.name = o.optString("name", "?");
                e.size = o.optLong("size", 0);
                e.time = o.optLong("time", 0);
                e.sent = o.optBoolean("sent", true);
                e.peer = o.optString("peer", "");
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
