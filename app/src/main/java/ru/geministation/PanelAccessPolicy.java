package ru.geministation;

/** Only the station's fixed display assets bypass panel authentication. */
final class PanelAccessPolicy {
    static boolean stationLedAsset(String method, String path, String remoteIp) {
        return "GET".equals(method) && path != null
                && path.matches("/led/(listening|thinking|speaking|clear)\\.gif")
                && WebSecurity.stationLoopback(remoteIp);
    }
    private PanelAccessPolicy() { }
}
