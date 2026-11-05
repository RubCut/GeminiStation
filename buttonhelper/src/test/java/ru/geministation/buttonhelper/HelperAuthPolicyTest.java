package ru.geministation.buttonhelper;

import org.junit.Test;
import static org.junit.Assert.*;

public class HelperAuthPolicyTest {
    @Test public void onlyPinnedHelperHashPasses() {
        byte[] pinned = new byte[32], same = pinned.clone(), changed = pinned.clone(); changed[31] = 1;
        assertTrue(HelperAuthPolicy.sameHelper(same, pinned));
        assertFalse(HelperAuthPolicy.sameHelper(changed, pinned));
        assertFalse(HelperAuthPolicy.sameHelper(null, pinned));
        assertFalse(HelperAuthPolicy.sameHelper(same, new byte[16]));
    }
}
