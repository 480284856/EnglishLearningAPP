package cn.wordflow.app;

import android.content.Context;
import android.net.*;

final class NetworkStatus {
  static boolean online(Context context) {
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    NetworkCapabilities caps =
        manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
    return caps != null
        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
  }
}
