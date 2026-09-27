package com.drex.quasar;

import android.bluetooth.BluetoothSocket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Protocolo Quasar v1 sobre streams (TCP vía Wi-Fi Direct o RFCOMM vía Bluetooth).
 * - Stream 0: BEGIN + METAs, luego participa en chunks.
 * - Archivos >= 32MB se dividen en chunks de 1MB repartidos entre hasta 4 streams paralelos.
 * - Buffers de 256KB, sin lecturas byte a byte en datos.
 */
public class TransferEngine {

    public static final int PORT = 8988;
    public static final int MAX_STREAMS = 4;
    static final int BUF = 256 * 1024;
    private static final int CHUNK = 1024 * 1024;
    private static final long CHUNK_THRESHOLD = 32L * 1024 * 1024;

    /** Canal de transporte agnóstico (TCP o Bluetooth). */
    public static class Chan {
        public final InputStream in;
        public final OutputStream out;
        private final Closeable closeable;

        public Chan(InputStream in, OutputStream out, Closeable c) {
            this.in = in;
            this.out = out;
            this.closeable = c;
        }

        public void close() {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }

        public static Chan tcp(Socket s) throws IOException {
            return new Chan(
                    new BufferedInputStream(s.getInputStream(), BUF),
                    new BufferedOutputStream(s.getOutputStream(), BUF),
                    s);
        }

        public static Chan bt(BluetoothSocket s) throws IOException {
            return new Chan(
                    new BufferedInputStream(s.getInputStream(), BUF),
                    new BufferedOutputStream(s.getOutputStream(), BUF),
                    new Closeable() {
                        @Override public void close() throws IOException {
                            s.close();
                        }
                    });
        }
    }

    public interface Progress {
        void onFileStart(String name, long size, int index, int total);
        void onProgress(long done, long total, double speedMBs, String curName);
        boolean isCancelled();
    }

    public interface ReceiveTarget {
        void write(long offset, byte[] data, int len) throws Exception;
        long received();
        void complete() throws Exception;
        void abort();
    }

    public interface TargetFactory {
        ReceiveTarget create(String name, long size, String mime) throws Exception;
        void allComplete() throws Exception;
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        if (b == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "UTF-8");
    }

    static void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes("UTF-8"));
        out.flush();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "archivo" : s, "UTF-8");
        } catch (Exception e) {
            return "archivo";
        }
    }

    static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static class Chunk {
        int fileIdx;
        long offset;
        int len;
    }

    // ------------------------------ SENDER ------------------------------
    public static void send(List<Chan> chans, final List<ShareState.SendFile> files, final Progress p) throws Exception {
        long total = 0;
        for (ShareState.SendFile f : files) total += f.size;
        final long totalF = total;

        OutputStream out0 = chans.get(0).out;
        writeLine(out0, "BEGIN|" + files.size() + "|" + totalF);
        for (int i = 0; i < files.size(); i++) {
            ShareState.SendFile f = files.get(i);
            writeLine(out0, "META|" + i + "|" + enc(f.name) + "|" + f.size + "|" + enc(f.mime));
        }

        final ArrayDeque<Chunk> queue = new ArrayDeque<>();
        for (int i = 0; i < files.size(); i++) {
            long size = files.get(i).size;
            long off = 0;
            while (off < size) {
                int len = (int) Math.min(CHUNK, size - off);
                Chunk c = new Chunk();
                c.fileIdx = i;
                c.offset = off;
                c.len = len;
                queue.add(c);
                off += len;
            }
        }

        int nStreams = (totalF >= CHUNK_THRESHOLD && chans.size() > 1)
                ? Math.min(chans.size(), MAX_STREAMS) : 1;
        final AtomicLong done = new AtomicLong(0);
        final SpeedMeter meter = new SpeedMeter();
        final Exception[] err = new Exception[1];
        final AtomicInteger announced = new AtomicInteger(-1);

        Thread reporter = new Thread(() -> {
            try {
                while (!p.isCancelled() && err[0] == null) {
                    long d = done.get();
                    int cur = announced.get();
                    String curName = (cur >= 0 && cur < files.size()) ? files.get(cur).name : "";
                    p.onProgress(d, totalF, meter.getMBs(), curName);
                    if (d >= totalF) break;
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        });
        reporter.start();

        List<Thread> threads = new ArrayList<>();
        for (int s = 0; s < nStreams; s++) {
            final Chan ch = chans.get(s);
            Thread t = new Thread(() -> {
                RandomAccessFile raf = null;
                int curFile = -1;
                try {
                    OutputStream out = ch.out;
                    byte[] buf = new byte[BUF];
                    while (!p.isCancelled() && err[0] == null) {
                        Chunk c;
                        synchronized (queue) {
                            c = queue.poll();
                        }
                        if (c == null) break;
                        int prev = announced.get();
                        if (c.fileIdx > prev && announced.compareAndSet(prev, c.fileIdx)) {
                            ShareState.SendFile sf = files.get(c.fileIdx);
                            p.onFileStart(sf.name, sf.size, c.fileIdx, files.size());
                        }
                        ShareState.SendFile f = files.get(c.fileIdx);
                        if (curFile != c.fileIdx) {
                            if (raf != null) try {
                                raf.close();
                            } catch (Exception ignored) {
                            }
                            raf = new RandomAccessFile(f.file, "r");
                            curFile = c.fileIdx;
                        }
                        writeLine(out, "CHUNK|" + c.fileIdx + "|" + c.offset + "|" + c.len);
                        raf.seek(c.offset);
                        long remaining = c.len;
                        while (remaining > 0) {
                            int r = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                            if (r < 0) throw new IOException("Fin inesperado del archivo");
                            out.write(buf, 0, r);
                            remaining -= r;
                            done.addAndGet(r);
                            meter.add(r);
                        }
                        out.flush();
                    }
                    try {
                        writeLine(out, "BYE");
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    if (err[0] == null) err[0] = e;
                } finally {
                    if (raf != null) try {
                        raf.close();
                    } catch (Exception ignored) {
                    }
                    ch.close();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        reporter.interrupt();
        try {
            reporter.join(1500);
        } catch (InterruptedException ignored) {
        }
        if (p.isCancelled()) throw new Exception("cancelado");
        if (err[0] != null) throw err[0];
        if (done.get() < totalF) throw new Exception("Transferencia incompleta");
        p.onProgress(totalF, totalF, meter.getMBs(), "");
    }

    // ------------------------------ RECEIVER ------------------------------
    public static void receive(List<Chan> chans, final TargetFactory factory, final Progress p) throws Exception {
        InputStream in0 = chans.get(0).in;
        String begin = readLine(in0);
        if (begin == null || !begin.startsWith("BEGIN|")) throw new IOException("Protocolo inválido");
        String[] bp = begin.split("\\|");
        final int count = Integer.parseInt(bp[1]);
        final long total = Long.parseLong(bp[2]);

        final ReceiveTarget[] targets = new ReceiveTarget[count];
        final String[] names = new String[count];
        final long[] sizes = new long[count];
        for (int i = 0; i < count; i++) {
            String meta = readLine(in0);
            if (meta == null || !meta.startsWith("META|")) throw new IOException("Protocolo inválido");
            String[] mp = meta.split("\\|", 5);
            int idx = Integer.parseInt(mp[1]);
            names[idx] = dec(mp[2]);
            sizes[idx] = Long.parseLong(mp[3]);
            String mime = mp.length > 4 ? dec(mp[4]) : "application/octet-stream";
            targets[idx] = factory.create(names[idx], sizes[idx], mime);
            p.onFileStart(names[idx], sizes[idx], idx, count);
        }

        final AtomicLong done = new AtomicLong(0);
        final SpeedMeter meter = new SpeedMeter();
        final Exception[] err = new Exception[1];
        final AtomicLongArray fileDone = new AtomicLongArray(count);

        Thread reporter = new Thread(() -> {
            try {
                while (!p.isCancelled() && err[0] == null) {
                    long d = done.get();
                    int cur = -1;
                    for (int i = 0; i < count; i++) {
                        if (fileDone.get(i) < sizes[i]) {
                            cur = i;
                            break;
                        }
                    }
                    String curName = cur >= 0 ? names[cur] : "";
                    p.onProgress(d, total, meter.getMBs(), curName);
                    if (d >= total) break;
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        });
        reporter.start();

        List<Thread> threads = new ArrayList<>();
        for (final Chan ch : chans) {
            Thread t = new Thread(() -> {
                try {
                    InputStream in = ch.in;
                    while (!p.isCancelled() && err[0] == null) {
                        String line = readLine(in);
                        if (line == null || line.equals("BYE")) break;
                        if (!line.startsWith("CHUNK|")) continue;
                        String[] cp = line.split("\\|");
                        int fi = Integer.parseInt(cp[1]);
                        long off = Long.parseLong(cp[2]);
                        int len = Integer.parseInt(cp[3]);
                        byte[] data = new byte[len];
                        int got = 0;
                        while (got < len) {
                            int r = in.read(data, got, len - got);
                            if (r < 0) throw new IOException("Conexión cortada");
                            got += r;
                        }
                        targets[fi].write(off, data, len);
                        done.addAndGet(len);
                        fileDone.addAndGet(fi, len);
                        meter.add(len);
                    }
                } catch (Exception e) {
                    if (err[0] == null) err[0] = e;
                } finally {
                    ch.close();
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        reporter.interrupt();
        try {
            reporter.join(1500);
        } catch (InterruptedException ignored) {
        }
        if (p.isCancelled()) {
            for (ReceiveTarget rt : targets) if (rt != null) rt.abort();
            throw new Exception("cancelado");
        }
        if (err[0] != null) {
            for (ReceiveTarget rt : targets) if (rt != null) rt.abort();
            throw err[0];
        }
        for (int i = 0; i < count; i++) {
            if (targets[i].received() < sizes[i]) {
                targets[i].abort();
                throw new Exception("Archivo incompleto: " + names[i]);
            }
            targets[i].complete();
        }
        factory.allComplete();
        p.onProgress(total, total, meter.getMBs(), "");
    }
}
