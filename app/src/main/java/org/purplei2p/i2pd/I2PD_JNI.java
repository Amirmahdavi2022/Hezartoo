package org.purplei2p.i2pd;

/**
 * JNI entry points of libi2pd.so from PurpleI2P's i2pd-android (BSD-3-Clause).
 * The package and class name are fixed by the library: its functions are
 * exported as Java_org_purplei2p_i2pd_I2PD_1JNI_*, so this file must keep the
 * exact name. Only the calls Hezartoo uses are declared; CI checks that each
 * one exists in the shipped library.
 */
public final class I2PD_JNI {
    private I2PD_JNI() {}

    public static void loadLibraries() {
        System.loadLibrary("i2pd");
    }

    public static native String getABICompiledWith();

    /** "ok" when the daemon started, otherwise an error text. */
    public static native String startDaemon();

    public static native void stopDaemon();

    public static native void setDataDir(String dataDir);

    public static native void setLanguage(String language);

    public static native boolean getHTTPProxyState();

    public static native void onNetworkStateChanged(boolean isConnected);
}
