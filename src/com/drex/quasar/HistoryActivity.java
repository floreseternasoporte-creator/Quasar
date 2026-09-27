package com.drex.quasar;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Date;
import java.util.List;

public class HistoryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        List<HistoryStore.Entry> entries = HistoryStore.load(this);
        ListView list = findViewById(R.id.list_history);
        findViewById(R.id.txt_empty).setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Object getItem(int p) { return entries.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View v, ViewGroup parent) {
                if (v == null) v = LayoutInflater.from(HistoryActivity.this)
                        .inflate(R.layout.item_history, parent, false);
                HistoryStore.Entry e = entries.get(p);
                TextView arrow = v.findViewById(R.id.hist_arrow);
                arrow.setText(e.sent ? "↑" : "↓");
                arrow.setTextColor(e.sent ? 0xFFA5B4FC : 0xFF22D3EE);
                ((TextView) v.findViewById(R.id.hist_name)).setText(e.name);
                String date = DateFormat.getDateFormat(HistoryActivity.this).format(new Date(e.time));
                String peer = e.peer == null || e.peer.isEmpty() ? "" : " · " + e.peer;
                ((TextView) v.findViewById(R.id.hist_detail)).setText(
                        (e.sent ? getString(R.string.sent) : getString(R.string.received))
                                + " · " + FileUtil.formatSize(e.size) + " · " + date + peer);
                return v;
            }
        });
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.slide_out_down);
    }
}
