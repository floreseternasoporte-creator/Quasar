package com.drex.quasar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.WpsInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransferService extends Service {

    public static final String ACTION_SEND = "com.drex.quasar.SEND";
    public static final String ACTION_RECEIVE = "com.drex.quasar.RECEIVE";
    public static final String ACTION_STOP = "com.drex.quasar.STOP";

    private static final String CH_ID = "quasar";
    private static final int NOTIF_ID = 77;
    private static final UUID BT_UUID = UUID.fromString("a1e2c3d4-b5f6-4789-a0b1-c2d3e4f5a6b7");
    private static final long STREAM_THRESHOLD = 32L * 1024 * 1024;

    private static TransferService instance;
    public static TransferService get() { return instance; }

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver p2pReceiver;
    private BroadcastReceiver btReceiver;
    private BluetoothAdapter btAdapter;
    private ServerSocket serverSocket;
    private BluetoothServerSocket btServerSocket;
    private NotificationManager notifMgr;
    private volatile boolean serviceRunning = false;
    private final AtomicBoolean transferStarted = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false); // 1.1: evita toques dobles y pisos entre transportes
    private Thread workThread;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
            if (p2p != null) channel = p2p.initialize(this, getMainLooper(), null);
        } catch (Exception e) {
            p2p = null;
        }
        try {
            btAdapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Exception e) {
            btAdapter = null;
        }
        notifMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            notifMgr.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String a = intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotif(getString(R.string.notif_searching), -1));
        serviceRunning = true;
        transferStarted.set(false);
        connecting.set(false); // 1.1: cada sesión arranca sin conexión en curso
        registerP2pReceiver();
        if (ACTION_SEND.equals(a)) {
            ShareState.isSender = true;
            ShareState.phase = ShareState.Phase.DISCOVERING;
            startWifiDiscovery();
            startBtDiscovery();
        } else if (ACTION_RECEIVE.equals(a)) {
            ShareState.isSender = false;
            ShareState.phase = ShareState.Phase.DISCOVERING;
            startWifiDiscovery();
            startBtListen();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAll();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------- notificación -------------------------
    private Notification buildNotif(String text, int progress) {
        Intent i = new Intent(this, TransferActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH_ID)
                .setContentTitle("Quasar")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notif) // 1.1: silueta alfa, no el ícono a color
                .setContentIntent(pi)
                .setOngoing(true);
        if (progress >= 0) b.setProgress(100, progress, false);
        return b.build();
    }

    private void updateNotif(String text, int progress) {
        try {
            notifMgr.notify(NOTIF_ID, buildNotif(text, progress));
        } catch (Exception ignored) {
        }
    }

    // ------------------------- Wi-Fi Direct -------------------------
    private void registerP2pReceiver() {
        if (p2pReceiver != null || p2p == null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String a = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                    try {
                        p2p.requestPeers(channel, peers -> {
                            ShareState.wifiPeers.clear();
                            for (WifiP2pDevice d : peers.getDeviceList()) {
                                if (d.status == WifiP2pDevice.AVAILABLE
                                        || d.status == WifiP2pDevice.INVITED
                                        || d.status == WifiP2pDevice.CONNECTED) {
                                    boolean dup = false;
                                    for (WifiP2pDevice x : ShareState.wifiPeers)
                                        if (x.deviceAddress.equals(d.deviceAddress)) { dup = true; break; }
                                    if (!dup) ShareState.wifiPeers.add(d);
                                }
                            }
                        });
                    } catch (SecurityException se) {
                        setError("Sin permiso de dispositivos cercanos");
                    }
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                    NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (ni != null && ni.isConnected()
                            && ShareState.phase != ShareState.Phase.TRANSFERRING
                            && !transferStarted.get()) {
                        ShareState.phase = ShareState.Phase.CONNECTING;
                        try {
                            p2p.requestConnectionInfo(channel, TransferService.this::onConnectionInfo);
                        } catch (SecurityException se) {
                            setError("Sin permiso de dispositivos cercanos");
                        }
                    }
                }
            }
        };
        registerReceiver(p2pReceiver, f);
    }

    private void startWifiDiscovery() {
        if (p2p == null || channel == null) {
            setError("Este teléfono no soporta Wi-Fi Direct");
            return;
        }
        try {
            p2p.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) {
                    if (reason != WifiP2pManager.P2P_UNSUPPORTED)
                        startWifiDiscoveryRetry();
                    else setError("Wi-Fi Direct no disponible aquí");
                }
            });
        } catch (SecurityException se) {
            setError("Sin permiso de dispositivos cercanos");
        } catch (Exception e) {
            setError("No se pudo iniciar la búsqueda");
        }
    }

    private void startWifiDiscoveryRetry() {
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            if (serviceRunning && ShareState.phase == ShareState.Phase.DISCOVERING) startWifiDiscovery();
        }).start();
    }

    public void connectWifiPeer(WifiP2pDevice device) {
        if (p2p == null || channel == null) {
            setError("Wi-Fi Direct no disponible");
            return;
        }
        if (!connecting.compareAndSet(false, true)) return; // 1.1: evita toques dobles
        teardownBtDiscovery(); // 1.1: el descubrimiento BT interfiere con el Wi-Fi
        ShareState.useBluetooth = false;
        ShareState.phase = ShareState.Phase.CONNECTING;
        ShareState.peerName = device.deviceName;
        updateNotif(getString(R.string.connecting), -1);
        try {
            try { p2p.stopPeerDiscovery(channel, null); } catch (Exception ignored) {}
            WifiP2pConfig cfg = new WifiP2pConfig();
            cfg.deviceAddress = device.deviceAddress;
            cfg.wps.setup = WpsInfo.PBC;
            p2p.connect(channel, cfg, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) {
                    connecting.set(false); // 1.1: libera para reintentar
                    setError("No se pudo conectar (código " + reason + "). Acerca los teléfonos e inténtalo de nuevo.");
                }
            });
        } catch (SecurityException se) {
            setError("Sin permiso de dispositivos cercanos");
        }
    }

    private void onConnectionInfo(WifiP2pInfo info) {
        if (!info.groupFormed || !transferStarted.compareAndSet(false, true)) return;
        connecting.set(false); // 1.1: la conexión cuajó
        try { p2p.stopPeerDiscovery(channel, null); } catch (Exception ignored) {}
        startWork(() -> {
            try {
                if (info.isGroupOwner) {
                    runAsGroupOwner();
                } else {
                    runAsClient(info);
                }
            } catch (Exception e) {
                setError("Conexión fallida: " + friendly(e));
            }
        });
    }

    private void runAsGroupOwner() throws Exception {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(TransferEngine.PORT));
        Socket s0 = serverSocket.accept();
        tune(s0);
        // handshake: el cliente dice su rol (lectura byte a byte, sin over-read)
        String hi = TransferEngine.readLine(s0.getInputStream());
        boolean clientIsSender = hi != null && hi.trim().endsWith("SENDER");
        // 1.1: si ambos eligieron el mismo rol, rechazar con mensaje claro
        boolean mismatch = (clientIsSender && ShareState.isSender)
                || (!clientIsSender && !ShareState.isSender);
        if (mismatch) {
            try {
                TransferEngine.writeLine(s0.getOutputStream(),
                        "ROLE_ERR|" + (clientIsSender ? "both_sender" : "both_receiver"));
            } catch (Exception ignored) {}
            try { s0.close(); } catch (Exception ignored) {}
            throw new Exception(clientIsSender
                    ? "Ambos teléfonos eligieron ENVIAR. Uno debe elegir RECIBIR."
                    : "Ambos teléfonos eligieron RECIBIR. Uno debe elegir ENVIAR.");
        }
        TransferEngine.writeLine(s0.getOutputStream(), "ROLE_OK");
        List<TransferEngine.Chan> chans = new ArrayList<>();
        chans.add(TransferEngine.Chan.tcp(s0));
        if (clientIsSender) {
            ShareState.peerName = peerLabel("Wi-Fi Direct");
            acceptMore(chans);
            runReceive(chans);
        } else {
            int n = streamsFor(totalToSend());
            TransferEngine.writeLine(s0.getOutputStream(), "STREAMS|" + n);
            acceptMore(chans);
            runSend(chans);
        }
    }

    private void runAsClient(WifiP2pInfo info) throws Exception {
        List<TransferEngine.Chan> chans = new ArrayList<>();
        Socket s0 = new Socket();
        tune(s0);
        s0.connect(new InetSocketAddress(info.groupOwnerAddress, TransferEngine.PORT), 15000);
        chans.add(TransferEngine.Chan.tcp(s0));
        TransferEngine.writeLine(s0.getOutputStream(), "HI|" + (ShareState.isSender ? "SENDER" : "RECEIVER"));
        // 1.1: confirma el rol del otro lado (null = par 1.0, sigue como antes)
        String role = readRoleLine(s0);
        if (role != null && role.startsWith("ROLE_ERR")) throw new Exception(roleMessage(role));
        if (ShareState.isSender) {
            int n = streamsFor(totalToSend());
            for (int i = 1; i < n; i++) {
                Socket s = new Socket();
                tune(s);
                s.connect(new InetSocketAddress(info.groupOwnerAddress, TransferEngine.PORT), 15000);
                chans.add(TransferEngine.Chan.tcp(s));
            }
            runSend(chans);
        } else {
            String line = TransferEngine.readLine(s0.getInputStream());
            int n = 1;
            try {
                if (line != null && line.startsWith("STREAMS|")) n = Integer.parseInt(line.substring(8).trim());
            } catch (Exception ignored) {}
            n = Math.max(1, Math.min(n, TransferEngine.MAX_STREAMS));
            for (int i = 1; i < n; i++) {
                Socket s = new Socket();
                tune(s);
                s.connect(new InetSocketAddress(info.groupOwnerAddress, TransferEngine.PORT), 15000);
                chans.add(TransferEngine.Chan.tcp(s));
            }
            runReceive(chans);
        }
    }

    private void acceptMore(List<TransferEngine.Chan> chans) {
        try {
            serverSocket.setSoTimeout(2500);
            while (chans.size() < TransferEngine.MAX_STREAMS) {
                try {
                    Socket s = serverSocket.accept();
                    tune(s);
                    chans.add(TransferEngine.Chan.tcp(s));
                } catch (SocketTimeoutException ste) {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(serverSocket);
            serverSocket = null;
        }
    }

    private void tune(Socket s) throws Exception {
        s.setTcpNoDelay(true);
        s.setSendBufferSize(512 * 1024);
        s.setReceiveBufferSize(512 * 1024);
    }

    // ------------------------- ayudas 1.1 -------------------------
    /** Apaga el descubrimiento Bluetooth para no interferir con el Wi-Fi. */
    private void teardownBtDiscovery() {
        try {
            if (btAdapter != null) btAdapter.cancelDiscovery();
        } catch (Exception ignored) {}
    }

    /** Apaga el lado Wi-Fi para que no se pise con una conexión Bluetooth. */
    private void teardownWifi() {
        try {
            if (p2p != null && channel != null) {
                p2p.stopPeerDiscovery(channel, null);
                p2p.cancelConnect(channel, null);
                p2p.removeGroup(channel, null);
            }
        } catch (Exception ignored) {}
    }

    /** Lee la línea ROLE con timeout: null si el par es 1.0 (no la envía). */
    private String readRoleLine(Socket s) {
        try {
            s.setSoTimeout(8000);
            try {
                return TransferEngine.readLine(s.getInputStream());
            } finally {
                try { s.setSoTimeout(0); } catch (Exception ignored) {}
            }
        } catch (SocketTimeoutException ste) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String roleMessage(String role) {
        if (role.contains("both_sender"))
            return "Ambos teléfonos eligieron ENVIAR. Uno debe elegir RECIBIR.";
        if (role.contains("both_receiver"))
            return "Ambos teléfonos eligieron RECIBIR. Uno debe elegir ENVIAR.";
        return "Roles incompatibles con el otro teléfono.";
    }

    private long totalToSend() {
        long t = 0;
        for (ShareState.SendFile f : ShareState.sendFiles) t += f.size;
        return t;
    }

    private int streamsFor(long total) {
        return total >= 32L * 1024 * 1024 ? TransferEngine.MAX_STREAMS : 1;
    }

    private String peerLabel(String fallback) {
        return ShareState.peerName == null || ShareState.peerName.isEmpty() ? fallback : ShareState.peerName;
    }

    // ------------------------- Bluetooth -------------------------
    private void startBtDiscovery() {
        if (btAdapter == null) return;
        // 1.1: al reintentar, desregistra el receiver viejo para no fugarlo
        try {
            if (btReceiver != null) unregisterReceiver(btReceiver);
        } catch (Exception ignored) {}
        btReceiver = null;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) addBtPeer(d);
                }
            }
        };
        try {
            registerReceiver(btReceiver, f);
            for (BluetoothDevice d : btAdapter.getBondedDevices()) addBtPeer(d);
            if (btAdapter.isDiscovering()) btAdapter.cancelDiscovery();
            btAdapter.startDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    private void addBtPeer(BluetoothDevice d) {
        for (BluetoothDevice x : ShareState.btPeers)
            if (x.getAddress().equals(d.getAddress())) return;
        ShareState.btPeers.add(d);
    }

    private void startBtListen() {
        if (btAdapter == null) return;
        new Thread(() -> {
            try {
                btServerSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord("Quasar", BT_UUID);
                while (serviceRunning && !transferStarted.get()) {
                    BluetoothSocket sock;
                    try {
                        sock = btServerSocket.accept();
                    } catch (Exception e) {
                        break;
                    }
                    if (sock != null && transferStarted.compareAndSet(false, true)) {
                        try {
                            ShareState.peerName = sock.getRemoteDevice().getName();
                        } catch (SecurityException ignored) {
                        }
                        runBtReceive(sock);
                        break;
                    } else if (sock != null) {
                        try { sock.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }
        }).start();
    }

    public void connectBt(BluetoothDevice device) {
        if (!connecting.compareAndSet(false, true)) return; // 1.1: evita toques dobles
        transferStarted.set(true); // 1.1: el Wi-Fi ya no puede pisar esta conexión
        teardownWifi(); // 1.1: que el Wi-Fi no se pise con el Bluetooth
        ShareState.useBluetooth = true;
        ShareState.phase = ShareState.Phase.CONNECTING;
        try {
            ShareState.peerName = device.getName();
        } catch (SecurityException ignored) {
            ShareState.peerName = "Bluetooth";
        }
        updateNotif(getString(R.string.connecting), -1);
        startWork(() -> {
            try {
                try { btAdapter.cancelDiscovery(); } catch (Exception ignored) {}
                BluetoothSocket sock = device.createInsecureRfcommSocketToServiceRecord(BT_UUID);
                sock.connect();
                List<TransferEngine.Chan> chans = new ArrayList<>();
                chans.add(TransferEngine.Chan.bt(sock));
                runSend(chans);
            } catch (Exception e) {
                connecting.set(false); // 1.1: libera para reintentar
                transferStarted.set(false);
                setError("Bluetooth falló: " + friendly(e));
            }
        });
    }

    private void runBtReceive(BluetoothSocket sock) {
        startWork(() -> {
            try {
                List<TransferEngine.Chan> chans = new ArrayList<>();
                chans.add(TransferEngine.Chan.bt(sock));
                runReceive(chans);
            } catch (Exception e) {
                setError("Bluetooth falló: " + friendly(e));
            }
        });
    }

    // ------------------------- motor -------------------------
    private void startWork(Runnable r) {
        workThread = new Thread(r);
        workThread.start();
    }

    private TransferEngine.Progress makeProgress() {
        return new TransferEngine.Progress() {
            @Override
            public void onFileStart(String name, long size, int index, int total) {
                ShareState.currentFile = name;
                ShareState.fileBytes = size;
                ShareState.fileDone = 0;
            }

            @Override
            public void onProgress(long done, long total, double speedMBs, String curName) {
                ShareState.doneBytes = done;
                ShareState.totalBytes = total;
                ShareState.speedMBs = speedMBs;
                if (curName != null && !curName.isEmpty()) ShareState.currentFile = curName;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                updateNotif((ShareState.isSender ? "Enviando" : "Recibiendo") + "… " + pct + "%", pct);
            }

            @Override
            public boolean isCancelled() {
                return ShareState.cancelRequested || !serviceRunning;
            }
        };
    }

    private void runSend(List<TransferEngine.Chan> chans) {
        ShareState.phase = ShareState.Phase.TRANSFERRING;
        ShareState.transferStartMs = SystemClock.elapsedRealtime();
        try {
            TransferEngine.send(chans, ShareState.sendFiles, makeProgress());
            for (ShareState.SendFile f : ShareState.sendFiles)
                HistoryStore.add(this, f.name, f.size, true, peerLabel(""));
            onTransferSuccess();
        } catch (Exception e) {
            onTransferError(e);
        } finally {
            for (TransferEngine.Chan c : chans) c.close();
            cleanupNet();
        }
    }

    private void runReceive(List<TransferEngine.Chan> chans) {
        ShareState.phase = ShareState.Phase.TRANSFERRING;
        ShareState.transferStartMs = SystemClock.elapsedRealtime();
        final List<String[]> receivedMeta = new ArrayList<>();
        try {
            TransferEngine.receive(chans, new TransferEngine.TargetFactory() {
                @Override
                public TransferEngine.ReceiveTarget create(String name, long size, String mime) throws Exception {
                    receivedMeta.add(new String[]{ name, String.valueOf(size) });
                    MediaStoreTarget t = new MediaStoreTarget(TransferService.this, name, size, mime);
                    t.create();
                    return t;
                }

                @Override
                public void allComplete() {}
            }, makeProgress());
            for (String[] m : receivedMeta)
                HistoryStore.add(this, m[0], Long.parseLong(m[1]), false, peerLabel(""));
            onTransferSuccess();
        } catch (Exception e) {
            onTransferError(e);
        } finally {
            for (TransferEngine.Chan c : chans) c.close();
            cleanupNet();
        }
    }

    private void onTransferSuccess() {
        ShareState.phase = ShareState.Phase.DONE;
        ShareState.transferEndMs = SystemClock.elapsedRealtime();
        ShareState.speedMBs = 0;
        updateNotif(getString(R.string.notif_done), 100);
        try { FileUtil.clearCache(this); } catch (Exception ignored) {}
    }

    private void onTransferError(Exception e) {
        if (ShareState.cancelRequested) {
            setError("Cancelado");
        } else {
            setError(friendly(e));
        }
    }

    private void setError(String msg) {
        connecting.set(false); // 1.1: libera para un reintento
        ShareState.phase = ShareState.Phase.ERROR;
        ShareState.error = msg;
        updateNotif("Quasar: " + msg, -1);
        cleanupNet();
    }

    private String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "Error de conexión";
        if (m.contains("ETIMEDOUT") || m.contains("timed out")) return "Tiempo de espera agotado. Acerca los teléfonos.";
        if (m.contains("ECONNREFUSED") || m.contains("refused")) return "El otro teléfono no está en modo Recibir.";
        if (m.contains("EHOSTUNREACH")) return "No se alcanzó al otro teléfono.";
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    private void cleanupNet() {
        closeQuietly(serverSocket);
        serverSocket = null;
        try {
            if (p2p != null && channel != null) p2p.removeGroup(channel, null);
        } catch (Exception ignored) {}
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }

    public void stopAll() {
        serviceRunning = false;
        connecting.set(false);      // 1.1: libera una conexión en curso
        transferStarted.set(false); // 1.1: la próxima sesión puede conectar de nuevo
        ShareState.cancelRequested = true;
        closeQuietly(serverSocket);
        serverSocket = null;
        closeQuietly(btServerSocket);
        btServerSocket = null;
        try {
            if (btAdapter != null) btAdapter.cancelDiscovery();
        } catch (Exception ignored) {}
        try {
            if (p2p != null && channel != null) {
                p2p.removeGroup(channel, null);
                p2p.cancelConnect(channel, null);
            }
        } catch (Exception ignored) {}
        try {
            if (p2pReceiver != null) unregisterReceiver(p2pReceiver);
        } catch (Exception ignored) {}
        p2pReceiver = null;
        try {
            if (btReceiver != null) unregisterReceiver(btReceiver);
        } catch (Exception ignored) {}
        btReceiver = null;
        if (workThread != null) workThread.interrupt();
        try { stopForeground(true); } catch (Exception ignored) {}
    }
}
