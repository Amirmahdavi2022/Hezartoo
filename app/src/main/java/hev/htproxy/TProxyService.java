package hev.htproxy;

/**
 * JNI entry points of hev-socks5-tunnel 2.18.0. The package and class name are
 * fixed by the library (its JNI_OnLoad looks up "hev/htproxy/TProxyService"),
 * and every signature here must match native_methods[] in src/hev-jni.c of the
 * pinned commit exactly, or JNI_OnLoad returns JNI_ERR. CI checks this.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    /** Starts the tunnel on its own native thread. False if it was already running or failed. */
    public static native boolean TProxyStartService(String configPath, int tunFd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** {tx packets, tx bytes, rx packets, rx bytes} */
    public static native long[] TProxyGetStats();
}
