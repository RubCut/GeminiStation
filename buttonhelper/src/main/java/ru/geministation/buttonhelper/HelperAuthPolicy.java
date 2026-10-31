package ru.geministation.buttonhelper;

import java.security.MessageDigest;

final class HelperAuthPolicy {
    static boolean sameHelper(byte[] current, byte[] pinned) {
        return current != null && pinned != null && current.length == 32 && pinned.length == 32
                && MessageDigest.isEqual(current, pinned);
    }
    private HelperAuthPolicy() { }
}
