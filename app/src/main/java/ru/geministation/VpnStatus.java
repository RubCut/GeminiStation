package ru.geministation;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** The default network as seen by this app; per-app VPN exclusions may use another network. */
final class VpnStatus {
    static boolean active(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException ignored) { return false; }
    }
    private VpnStatus() { }
}
