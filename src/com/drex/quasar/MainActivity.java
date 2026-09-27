package com.drex.quasar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Quasar 2.0 — pantalla principal con navegación inferior:
 * Enviar | Recibir | Historial. Onboarding solo la primera vez.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;
    private static final int REQ_BROWSE = 300;

    private View tabSend, tabReceive, tabHistory;
    private View navSend, navReceive, navHistory;
    private ImageView navSendIcon, navReceiveIcon, navHistoryIcon;
    private TextView navSendLabel, navReceiveLabel, navHistoryLabel;
    private int currentTab = -1;

    private BaseAdapter historyAdapter;
    private List<HistoryStore.Entry> historyEntries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Onboarding solo la primera vez
        if (!getSharedPreferences("quasar_prefs", MODE_PRIVATE)
                .getBoolean("onboarding_done", false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        tabSend = findViewById(R.id.tab_send);
        tabReceive = findViewById(R.id.tab_receive);
        tabHistory = findViewById(R.id.tab_history);
        navSend = findViewById(R.id.nav_send);
        navReceive = findViewById(R.id.nav_receive);
        navHistory = findViewById(R.id.nav_history);
        navSendIcon = findViewById(R.id.nav_send_icon);
        navReceiveIcon = findViewById(R.id.nav_receive_icon);
        navHistoryIcon = findViewById(R.id.nav_history_icon);
        navSendLabel = findViewById(R.id.nav_send_label);
        navReceiveLabel = findViewById(R.id.nav_receive_label);
        navHistoryLabel = findViewById(R.id.nav_history_label);

        pressFx(findViewById(R.id.btn_send));
        pressFx(findViewById(R.id.btn_receive));
        pressFx(navSend);
        pressFx(navReceive);
        pressFx(navHistory);

        findViewById(R.id.btn_send).setOnClickListener(v -> openBrowser());
        findViewById(R.id.btn_receive).setOnClickListener(v -> openTransfer("receive"));
        navSend.setOnClickListener(v -> selectTab(0));
        navReceive.setOnClickListener(v -> selectTab(1));
        navHistory.setOnClickListener(v -> selectTab(2));

        setupHistoryList();
        selectTab(0);
        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab == 2) refreshHistory();
    }

    // ---------------- pestañas ----------------

    private void selectTab(int t) {
        if (currentTab == t) return;
        currentTab = t;
        tabSend.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        tabReceive.setVisibility(t == 1 ? View.VISIBLE : View.GONE);
        tabHistory.setVisibility(t == 2 ? View.VISIBLE : View.GONE);
        View shown = t == 0 ? tabSend : t == 1 ? tabReceive : tabHistory;
        shown.setAlpha(0f);
        shown.setTranslationY(24f);
        shown.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        paintNav(navSendIcon, navSendLabel, t == 0);
        paintNav(navReceiveIcon, navReceiveLabel, t == 1);
        paintNav(navHistoryIcon, navHistoryLabel, t == 2);

        if (t == 2) refreshHistory();
    }

    private void paintNav(ImageView icon, TextView label, boolean active) {
        icon.setAlpha(active ? 1f : 0.45f);
        label.setTextColor(active ? 0xFFA5B4FC : 0xFF9CA3AF);
        if (active) {
            icon.setScaleX(0.7f);
            icon.setScaleY(0.7f);
            icon.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
        }
    }

    // ---------------- historial (pestaña) ----------------

    private void setupHistoryList() {
        ListView list = findViewById(R.id.list_history);
        historyAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return historyEntries.size();
            }

            @Override
            public Object getItem(int p) {
                return historyEntries.get(p);
            }

            @Override
            public long getItemId(int p) {
                return p;
            }

            @Override
            public View getView(int p, View v, ViewGroup parent) {
                if (v == null) {
                    v = LayoutInflater.from(MainActivity.this)
                            .inflate(R.layout.item_history, parent, false);
                    v.setAlpha(0f);
                    v.setTranslationX(48f);
                    v.animate().alpha(1f).translationX(0f).setDuration(240)
                            .setStartDelay(Math.min(p, 10) * 35L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
                HistoryStore.Entry e = historyEntries.get(p);
                TextView arrow = v.findViewById(R.id.hist_arrow);
                arrow.setText(e.sent ? "↑" : "↓");
                arrow.setTextColor(e.sent ? 0xFFA5B4FC : 0xFF22D3EE);
                ((TextView) v.findViewById(R.id.hist_name)).setText(e.name);
                String date = DateFormat.getDateFormat(MainActivity.this).format(new Date(e.time));
                String peer = e.peer == null || e.peer.isEmpty() ? "" : " · " + e.peer;
                ((TextView) v.findViewById(R.id.hist_detail)).setText(
                        (e.sent ? getString(R.string.sent) : getString(R.string.received))
                                + " · " + FileUtil.formatSize(e.size) + " · " + date + peer);
                return v;
            }
        };
        list.setAdapter(historyAdapter);
    }

    private void refreshHistory() {
        historyEntries = HistoryStore.load(this);
        historyAdapter.notifyDataSetChanged();
        findViewById(R.id.txt_empty).setVisibility(
                historyEntries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------- flujos ----------------

    private void openBrowser() {
        startActivityForResult(new Intent(this, FileBrowserActivity.class), REQ_BROWSE);
        overridePendingTransition(R.anim.slide_in_up, R.anim.hold);
    }

    private void openTransfer(String mode) {
        Intent i = new Intent(this, TransferActivity.class);
        i.putExtra("mode", mode);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_up, R.anim.hold);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BROWSE && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = data.getParcelableArrayListExtra("uris");
            if (uris != null && !uris.isEmpty()) {
                ShareState.resetForNew();
                ShareState.isSender = true;
                ShareState.pickedUris.addAll(uris);
                Intent i = new Intent(this, TransferActivity.class);
                i.putExtra("mode", "send");
                i.putExtra("skipPick", true);
                startActivity(i);
                overridePendingTransition(R.anim.slide_in_up, R.anim.hold);
            }
        }
    }

    private void pressFx(View v) {
        v.setOnTouchListener((view, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(110).start();
            } else if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(340)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
            }
            return false;
        });
    }

    private void ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (need.isEmpty()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_msg)
                .setPositiveButton(R.string.perm_ok, (d, w) ->
                        requestPermissions(need.toArray(new String[0]), REQ_PERMS))
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        boolean allOk = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) { allOk = false; break; }
        }
        if (!allOk && !isFinishing()) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                    .setTitle(R.string.perm_denied_title)
                    .setMessage(R.string.perm_denied_msg)
                    .setPositiveButton(R.string.open_settings, (d, w) -> {
                        try {
                            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton(R.string.continue_anyway, null)
                    .show();
        }
    }
}
