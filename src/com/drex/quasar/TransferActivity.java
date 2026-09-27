package com.drex.quasar;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TransferActivity extends Activity {

    private static final int REQ_PICK = 200;
    private static final int S_PICK = 0, S_DISCOVER = 1, S_TRANSFER = 2, S_DONE = 3;

    private boolean isSender;
    private int currentState = -1;

    private LinearLayout statePick, stateDiscover, stateTransfer, stateDone;
    private Button btnPick, btnToDiscover, btnCancelDiscover, btnCancelTransfer, btnDone;
    private TextView txtPickStatus, txtDiscoverTitle, txtDiscoverHint, txtNoPeers;
    private TextView txtSpeed, txtCurrentFile, txtPeer;
    private TextView txtDoneIcon, txtDoneTitle, txtDoneDetail;
    private TextView tabWifi, tabBt;
    private ListView listFiles, listPeers;
    private RadarView radar;
    private ProgressRingView ring;
    private SuccessBurstView burst; // 1.1: celebración de éxito

    private FileAdapter fileAdapter;
    private PeerAdapter peerAdapter;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private String lastBlipKey = "";
    private String lastPeerSig = ""; // 1.1: evita notifyDataSetChanged() cada 300ms

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        isSender = "send".equals(getIntent().getStringExtra("mode"));
        ShareState.resetForNew();
        ShareState.isSender = isSender;

        statePick = findViewById(R.id.state_pick);
        stateDiscover = findViewById(R.id.state_discover);
        stateTransfer = findViewById(R.id.state_transfer);
        stateDone = findViewById(R.id.state_done);
        btnPick = findViewById(R.id.btn_pick);
        btnToDiscover = findViewById(R.id.btn_to_discover);
        btnCancelDiscover = findViewById(R.id.btn_cancel_discover);
        btnCancelTransfer = findViewById(R.id.btn_cancel_transfer);
        btnDone = findViewById(R.id.btn_done);
        txtPickStatus = findViewById(R.id.txt_pick_status);
        txtDiscoverTitle = findViewById(R.id.txt_discover_title);
        txtDiscoverHint = findViewById(R.id.txt_discover_hint);
        txtNoPeers = findViewById(R.id.txt_no_peers);
        txtSpeed = findViewById(R.id.txt_speed);
        txtCurrentFile = findViewById(R.id.txt_current_file);
        txtPeer = findViewById(R.id.txt_peer);
        txtDoneIcon = findViewById(R.id.txt_done_icon);
        txtDoneTitle = findViewById(R.id.txt_done_title);
        txtDoneDetail = findViewById(R.id.txt_done_detail);
        tabWifi = findViewById(R.id.tab_wifi);
        tabBt = findViewById(R.id.tab_bt);
        listFiles = findViewById(R.id.list_files);
        listPeers = findViewById(R.id.list_peers);
        radar = findViewById(R.id.radar);
        ring = findViewById(R.id.ring);
        burst = findViewById(R.id.burst);

        fileAdapter = new FileAdapter();
        listFiles.setAdapter(fileAdapter);
        peerAdapter = new PeerAdapter();
        listPeers.setAdapter(peerAdapter);
        listPeers.setOnItemClickListener((parent, view, position, id) -> onPeerClicked(position));

        btnPick.setOnClickListener(v -> {
            // Quasar 2.0: explorador propio con miniaturas en vez del picker del sistema
            startActivityForResult(new Intent(this, FileBrowserActivity.class), REQ_PICK);
            overridePendingTransition(R.anim.slide_in_up, R.anim.hold);
        });

        btnToDiscover.setOnClickListener(v -> prepareAndDiscover());

        tabWifi.setOnClickListener(v -> setTransport(false));
        tabBt.setOnClickListener(v -> setTransport(true));

        btnCancelDiscover.setOnClickListener(v -> stopAndFinish());
        btnCancelTransfer.setOnClickListener(v -> stopAndFinish());
        // 1.1: "Reintentar" reinicia el flujo de verdad en vez de solo cerrar
        btnDone.setOnClickListener(v -> {
            if (ShareState.phase == ShareState.Phase.ERROR) {
                try {
                    startService(new Intent(this, TransferService.class)
                            .setAction(TransferService.ACTION_STOP));
                } catch (Exception ignored) {}
                Intent i = getIntent();
                finish();
                startActivity(i);
                overridePendingTransition(R.anim.state_in, R.anim.hold);
            } else {
                finish();
            }
        });
        // 1.1: rebote elástico al pulsar botones
        pressFx(btnPick);
        pressFx(btnToDiscover);
        pressFx(btnCancelDiscover);
        pressFx(btnCancelTransfer);
        pressFx(btnDone);

        if (isSender) {
            showState(S_PICK);
            // Quasar 2.0: si ya vienen archivos del explorador, saltar directo a descubrir
            if (getIntent().getBooleanExtra("skipPick", false)
                    && !ShareState.pickedUris.isEmpty()) {
                refreshPickedList();
                prepareAndDiscover();
            }
        } else {
            findViewById(R.id.transport_tabs).setVisibility(View.GONE);
            txtDiscoverTitle.setText(R.string.waiting);
            txtDiscoverHint.setText(R.string.waiting_hint);
            startServiceAction(TransferService.ACTION_RECEIVE);
            showState(S_DISCOVER);
        }

        pollHandler.post(poll);
    }

    private void startServiceAction(String action) {
        Intent i = new Intent(this, TransferService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void stopAndFinish() {
        startService(new Intent(this, TransferService.class).setAction(TransferService.ACTION_STOP));
        finish();
        overridePendingTransition(R.anim.hold, R.anim.slide_out_down);
    }

    @Override
    public void onBackPressed() {
        if (ShareState.phase == ShareState.Phase.TRANSFERRING) {
            finish(); // la transferencia sigue en segundo plano
            overridePendingTransition(R.anim.hold, R.anim.slide_out_down);
        } else {
            stopAndFinish();
        }
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacks(poll);
        if (ShareState.phase != ShareState.Phase.TRANSFERRING
                && ShareState.phase != ShareState.Phase.DONE) {
            try {
                startService(new Intent(this, TransferService.class).setAction(TransferService.ACTION_STOP));
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            // Quasar 2.0: el explorador propio devuelve la lista de URIs
            java.util.ArrayList<Uri> uris = data.getParcelableArrayListExtra("uris");
            ShareState.pickedUris.clear();
            if (uris != null) {
                ShareState.pickedUris.addAll(uris);
            } else if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++)
                    ShareState.pickedUris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                ShareState.pickedUris.add(data.getData());
            }
            for (Uri u : ShareState.pickedUris) {
                try {
                    getContentResolver().takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }
            refreshPickedList();
        }
    }

    private void refreshPickedList() {
        List<String[]> rows = new ArrayList<>();
        for (Uri u : ShareState.pickedUris) {
            String name = FileUtil.getName(this, u);
            long size = FileUtil.getSize(this, u);
            rows.add(new String[]{ name, size >= 0 ? FileUtil.formatSize(size) : "" });
        }
        fileAdapter.setRows(rows);
        if (rows.isEmpty()) {
            txtPickStatus.setText("");
            btnToDiscover.setEnabled(false);
        } else {
            txtPickStatus.setText(rows.size() + " archivo(s) elegido(s)");
            btnToDiscover.setEnabled(true);
        }
    }

    private void prepareAndDiscover() {
        btnToDiscover.setEnabled(false);
        btnPick.setEnabled(false);
        txtPickStatus.setText(R.string.preparing);
        new Thread(() -> {
            try {
                ShareState.sendFiles.clear();
                FileUtil.clearCache(this);
                int i = 0;
                for (Uri u : ShareState.pickedUris) {
                    final int idx = ++i;
                    String name = FileUtil.getName(this, u);
                    File f = FileUtil.copyToCache(this, u, name,
                            (n, d, t) -> runOnUiThread(() ->
                                    txtPickStatus.setText("Preparando " + idx + "/" + ShareState.pickedUris.size() + "…")));
                    ShareState.SendFile sf = new ShareState.SendFile();
                    sf.name = name;
                    sf.size = f.length();
                    sf.mime = FileUtil.getMime(this, u);
                    sf.file = f;
                    ShareState.sendFiles.add(sf);
                }
                runOnUiThread(() -> {
                    startServiceAction(TransferService.ACTION_SEND);
                    showState(S_DISCOVER);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtPickStatus.setText("Error preparando archivos: " + e.getMessage());
                    btnToDiscover.setEnabled(true);
                    btnPick.setEnabled(true);
                });
            }
        }).start();
    }

    private void setTransport(boolean bt) {
        ShareState.useBluetooth = bt;
        tabWifi.setBackgroundResource(bt ? R.drawable.card : R.drawable.btn_primary);
        tabBt.setBackgroundResource(bt ? R.drawable.btn_primary : R.drawable.card);
        tabWifi.setTextColor(bt ? 0xFF9CA3AF : 0xFFFFFFFF);
        tabBt.setTextColor(bt ? 0xFFFFFFFF : 0xFF9CA3AF);
        peerAdapter.notifyDataSetChanged();
    }

    private void onPeerClicked(int position) {
        TransferService svc = TransferService.get();
        if (svc == null) return;
        if (ShareState.useBluetooth) {
            if (position < ShareState.btPeers.size())
                svc.connectBt(ShareState.btPeers.get(position));
        } else {
            if (position < ShareState.wifiPeers.size())
                svc.connectWifiPeer(ShareState.wifiPeers.get(position));
        }
    }

    private void showState(int s) {
        if (currentState == s) return;
        currentState = s;
        statePick.setVisibility(s == S_PICK ? View.VISIBLE : View.GONE);
        stateDiscover.setVisibility(s == S_DISCOVER ? View.VISIBLE : View.GONE);
        stateTransfer.setVisibility(s == S_TRANSFER ? View.VISIBLE : View.GONE);
        stateDone.setVisibility(s == S_DONE ? View.VISIBLE : View.GONE);
        View v = s == S_PICK ? statePick : s == S_DISCOVER ? stateDiscover
                : s == S_TRANSFER ? stateTransfer : stateDone;
        v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.state_in)); // 1.1: transición cinematográfica
        if (s == S_TRANSFER) ring.setProgressInstant(0);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            updateFromState();
            pollHandler.postDelayed(this, 300);
        }
    };

    private void updateFromState() {
        // radar blips (solo si cambió la lista)
        StringBuilder key = new StringBuilder();
        List<String> names = new ArrayList<>();
        if (!ShareState.useBluetooth) {
            for (WifiP2pDevice d : ShareState.wifiPeers) {
                names.add(d.deviceName);
                key.append(d.deviceAddress);
            }
        } else {
            for (BluetoothDevice d : ShareState.btPeers) {
                names.add(safeBtName(d));
                key.append(d.getAddress());
            }
        }
        if (!key.toString().equals(lastBlipKey)) {
            lastBlipKey = key.toString();
            radar.setPeerNames(names);
        }
        peerAdapter.setRowsIfChanged(peerSig(names));
        txtNoPeers.setVisibility(names.isEmpty()
                && ShareState.phase == ShareState.Phase.DISCOVERING ? View.VISIBLE : View.GONE);

        ShareState.Phase ph = ShareState.phase;
        if (ph == ShareState.Phase.CONNECTING) {
            txtDiscoverTitle.setText(R.string.connecting);
        }
        if (ph == ShareState.Phase.TRANSFERRING) {
            if (currentState != S_TRANSFER) showState(S_TRANSFER);
            long total = ShareState.totalBytes, done = ShareState.doneBytes;
            ring.setProgress(total > 0 ? (float) done / total : 0f);
            txtSpeed.setText(FileUtil.formatSpeed(ShareState.speedMBs));
            txtCurrentFile.setText(ShareState.currentFile);
            String pn = ShareState.peerName;
            txtPeer.setText(pn == null || pn.isEmpty() ? "" : "↔ " + pn);
        } else if (ph == ShareState.Phase.DONE) {
            if (currentState != S_DONE) showDone(false);
        } else if (ph == ShareState.Phase.ERROR) {
            if (currentState != S_DONE) showDone(true);
        }
    }

    private String safeBtName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /** 1.1: firma de la lista de peers; solo notifica si cambió algo. */
    private String peerSig(List<String> names) {
        StringBuilder sb = new StringBuilder(ShareState.useBluetooth ? "B:" : "W:");
        if (ShareState.useBluetooth) {
            for (BluetoothDevice d : ShareState.btPeers) sb.append(d.getAddress()).append(';');
        } else {
            for (WifiP2pDevice d : ShareState.wifiPeers) sb.append(d.deviceAddress).append(';');
        }
        sb.append('|').append(names.size());
        return sb.toString();
    }

    /** 1.1: rebote elástico al pulsar un botón. */
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

    private void showDone(boolean isError) {
        showState(S_DONE);
        if (isError) {
            burst.setVisibility(View.GONE);
            txtDoneIcon.setVisibility(View.VISIBLE);
            txtDoneIcon.setText("✕");
            txtDoneIcon.setTextColor(0xFFF87171);
            txtDoneTitle.setText(R.string.transfer_failed);
            txtDoneDetail.setText(ShareState.error == null || ShareState.error.isEmpty()
                    ? "" : ShareState.error);
            btnDone.setText(R.string.retry);
            txtDoneIcon.setScaleX(0.3f);
            txtDoneIcon.setScaleY(0.3f);
            txtDoneIcon.animate().scaleX(1f).scaleY(1f).setDuration(450)
                    .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
        } else {
            txtDoneIcon.setVisibility(View.GONE);
            burst.setVisibility(View.VISIBLE);
            burst.start(); // 1.1: celebración con anillos y partículas
            txtDoneTitle.setText(R.string.transfer_done);
            long secs = Math.max(1, (ShareState.transferEndMs - ShareState.transferStartMs) / 1000);
            String detail = FileUtil.formatSize(ShareState.totalBytes) + " en " + secs + " s";
            if (!isSender) detail += "\n" + getString(R.string.saved_to);
            txtDoneDetail.setText(detail);
            btnDone.setText(R.string.done);
        }
    }

    // ---------------- adaptadores ----------------
    private class FileAdapter extends BaseAdapter {
        private List<String[]> rows = new ArrayList<>();

        void setRows(List<String[]> r) {
            rows = r;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(TransferActivity.this)
                        .inflate(R.layout.item_file, parent, false);
                // 1.1: entrada animada del archivo
                v.setAlpha(0f);
                v.setTranslationX(56f);
                v.animate().alpha(1f).translationX(0f).setDuration(260)
                        .setStartDelay(Math.min(p, 8) * 40L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
            ((TextView) v.findViewById(R.id.file_name)).setText(rows.get(p)[0]);
            ((TextView) v.findViewById(R.id.file_size)).setText(rows.get(p)[1]);
            return v;
        }
    }

    private class PeerAdapter extends BaseAdapter {
        /** 1.1: solo notifica cuando la lista realmente cambió (no cada 300ms). */
        void setRowsIfChanged(String sig) {
            if (!sig.equals(lastPeerSig)) {
                lastPeerSig = sig;
                notifyDataSetChanged();
            }
        }

        @Override
        public int getCount() {
            return ShareState.useBluetooth ? ShareState.btPeers.size() : ShareState.wifiPeers.size();
        }

        @Override public Object getItem(int p) { return null; }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(TransferActivity.this)
                        .inflate(R.layout.item_peer, parent, false);
                // 1.1: entrada animada del dispositivo descubierto
                v.setAlpha(0f);
                v.setTranslationX(64f);
                v.animate().alpha(1f).translationX(0f).setDuration(300)
                        .setStartDelay(Math.min(p, 8) * 55L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
            TextView name = v.findViewById(R.id.peer_name);
            TextView addr = v.findViewById(R.id.peer_addr);
            TextView badge = v.findViewById(R.id.peer_badge);
            if (ShareState.useBluetooth) {
                BluetoothDevice d = ShareState.btPeers.get(p);
                name.setText(safeBtName(d));
                addr.setText(d.getAddress());
                badge.setText(R.string.slow_badge);
            } else {
                WifiP2pDevice d = ShareState.wifiPeers.get(p);
                name.setText(d.deviceName);
                addr.setText(d.deviceAddress);
                badge.setText(R.string.fast_badge);
            }
            return v;
        }
    }
}
