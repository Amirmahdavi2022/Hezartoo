package network.loki.lokinet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * VPN service around the Lokinet engine.
 *
 * The package, class name, native method signatures and the "impl" / "m_FD"
 * fields are fixed by the engine's JNI glue (jni/lokinet_daemon.cpp), so they
 * must stay exactly as they are. Everything else is ours.
 */
public class LokinetDaemon extends VpnService {
    static {
        System.loadLibrary("lokinet-android");
    }

    private static native ByteBuffer Obtain();
    private static native void Free(ByteBuffer buf);
    public native boolean Configure(LokinetConfig config);
    public native int Mainloop();
    public native boolean IsRunning();
    public native boolean Stop();
    public native void InjectVPNFD();
    public native int GetUDPSocket();
    private static native String DetectFreeRange();
    public native String DumpStatus();

    public static final String TAG = "Hezartoo";
    public static final String ACTION_STOP = "xyz.amjmc.hezartoo.STOP";
    public static final String EXTRA_EXIT = "exit";

    // Fixed tun range. The engine's own range detection needs a netlink dump
    // that Android 11+ refuses to apps, which is what crashed the old app.
    static final String TUN_IP = "10.67.0.1";
    static final int TUN_PREFIX = 16;
    static final String DNS = "9.9.9.9";

    /** read by the UI */
    public static volatile LokinetDaemon instance;
    public static volatile String state = "idle";
    public static volatile String lastError = "";
    public static volatile long startedAt = 0;

    ByteBuffer impl = null;
    int m_FD = -1;
    int m_UDPSocket = -1;
    private Thread loop;

    public static File logFile(File filesDir) {
        return new File(filesDir, "lokinet.log");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        foreground();
        if (loop != null && loop.isAlive()) return START_STICKY;
        String exit = intent != null ? intent.getStringExtra(EXTRA_EXIT) : null;
        if (exit == null || exit.trim().isEmpty()) exit = "exit.loki";
        final String exitNode = exit.trim();
        instance = this;
        lastError = "";
        state = "starting";
        startedAt = System.currentTimeMillis();
        loop = new Thread(() -> run(exitNode), "lokinet");
        loop.start();
        return START_STICKY;
    }

    private void run(String exitNode) {
        try {
            File dir = getFilesDir();
            // start from a clean config so the chosen exit always applies
            new File(dir, "lokinet.ini").delete();
            File log = logFile(dir);
            log.delete();

            impl = Obtain();
            if (impl == null) { fail("engine: Obtain returned null"); return; }

            LokinetConfig config = new LokinetConfig(dir.toString());
            config.AddDefaultValue("network", "exit-node", exitNode);
            config.AddDefaultValue("network", "ifaddr", TUN_IP + "/" + TUN_PREFIX);
            config.AddDefaultValue("dns", "upstream", DNS);
            config.AddDefaultValue("logging", "type", "file");
            config.AddDefaultValue("logging", "file", log.toString());
            config.AddDefaultValue("logging", "level", "info");
            if (!config.Load()) { fail("config: could not create lokinet.ini"); return; }

            Builder b = new Builder();
            b.setMtu(1500);
            b.addAddress(TUN_IP, TUN_PREFIX);
            b.addRoute("0.0.0.0", 0);
            b.addDnsServer(DNS);
            b.setSession("Hezartoo");
            ParcelFileDescriptor iface = b.establish();
            if (iface == null) { fail("vpn: permission missing (establish returned null)"); return; }
            m_FD = iface.detachFd();
            InjectVPNFD();

            state = "configuring";
            if (!Configure(config)) { fail("engine: Configure failed, see log"); return; }
            m_UDPSocket = GetUDPSocket();
            if (m_UDPSocket < 0 || !protect(m_UDPSocket)) {
                fail("engine: could not protect UDP socket (" + m_UDPSocket + ")");
                return;
            }
            state = "running";
            int rc = Mainloop();
            Log.i(TAG, "mainloop returned " + rc);
            if (!"stopping".equals(state)) fail("engine stopped (code " + rc + "), see log");
        } catch (Throwable t) {
            Log.e(TAG, "run", t);
            fail(t.toString());
        }
    }

    private void fail(String why) {
        Log.e(TAG, why);
        lastError = why;
        state = "failed";
    }

    private void shutdown() {
        state = "stopping";
        new Thread(() -> {
            try { if (IsRunning()) Stop(); } catch (Throwable t) { Log.e(TAG, "stop", t); }
            state = "idle";
            instance = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "lokinet-stop").start();
    }

    @Override
    public void onRevoke() {
        shutdown();
    }

    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("vpn", "Hezartoo", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "vpn")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Hezartoo")
                .setContentText("Lokinet")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }
}
