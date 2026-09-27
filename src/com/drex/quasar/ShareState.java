package com.drex.quasar;

import android.bluetooth.BluetoothDevice;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ShareState {
    public enum Phase { IDLE, PICKING, DISCOVERING, CONNECTING, TRANSFERRING, DONE, ERROR }

    public static volatile Phase phase = Phase.IDLE;
    public static volatile boolean isSender = true;
    public static volatile boolean useBluetooth = false;
    public static volatile String peerName = "";
    public static volatile String error = "";
    public static volatile long totalBytes = 0;
    public static volatile long doneBytes = 0;
    public static volatile String currentFile = "";
    public static volatile long fileBytes = 0;
    public static volatile long fileDone = 0;
    public static volatile double speedMBs = 0;
    public static volatile long transferStartMs = 0;
    public static volatile long transferEndMs = 0;
    public static volatile boolean cancelRequested = false;

    public static final CopyOnWriteArrayList<WifiP2pDevice> wifiPeers = new CopyOnWriteArrayList<>();
    public static final CopyOnWriteArrayList<BluetoothDevice> btPeers = new CopyOnWriteArrayList<>();
    public static final List<Uri> pickedUris = Collections.synchronizedList(new ArrayList<Uri>());

    public static class SendFile {
        public String name;
        public long size;
        public String mime;
        public File file;
    }
    public static final List<SendFile> sendFiles = Collections.synchronizedList(new ArrayList<SendFile>());

    public static synchronized void reset() {
        phase = Phase.IDLE;
        useBluetooth = false;
        peerName = "";
        error = "";
        totalBytes = 0;
        doneBytes = 0;
        currentFile = "";
        fileBytes = 0;
        fileDone = 0;
        speedMBs = 0;
        transferStartMs = 0;
        transferEndMs = 0;
        cancelRequested = false;
        wifiPeers.clear();
        btPeers.clear();
    }

    public static synchronized void resetForNew() {
        reset();
        pickedUris.clear();
        sendFiles.clear();
    }
}
