package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelAccessPolicyTest {
    @Test public void onlyFixedStationAssetsAreExempt() {
        assertTrue(PanelAccessPolicy.stationLedAsset("GET", "/led/listening.gif", "127.0.0.1"));
        assertTrue(PanelAccessPolicy.stationLedAsset("GET", "/led/clear.gif", "127.0.0.1"));
        assertFalse(PanelAccessPolicy.stationLedAsset("GET", "/led/listening.gif", "192.168.1.2"));
        assertFalse(PanelAccessPolicy.stationLedAsset("POST", "/led/listening.gif", "127.0.0.1"));
        assertFalse(PanelAccessPolicy.stationLedAsset("GET", "/api/logs", "127.0.0.1"));
        assertFalse(PanelAccessPolicy.stationLedAsset("GET", "/api/settings-backup", "127.0.0.1"));
        assertFalse(PanelAccessPolicy.stationLedAsset("GET", "/led/../api/logs", "127.0.0.1"));
    }
}
