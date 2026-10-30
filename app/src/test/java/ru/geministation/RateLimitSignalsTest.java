package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class RateLimitSignalsTest {
    @Test public void explicitQuotaErrorsOnly() {
        assertTrue(RateLimitSignals.apiError(429, ""));
        assertTrue(RateLimitSignals.apiError(8, "RESOURCE_EXHAUSTED"));
        assertTrue(RateLimitSignals.closeReason("429 RESOURCE_EXHAUSTED"));
        assertFalse(RateLimitSignals.apiError(403, "PERMISSION_DENIED"));
        assertFalse(RateLimitSignals.apiError(500, "INTERNAL"));
        assertFalse(RateLimitSignals.closeReason("status 1429"));
        assertFalse(RateLimitSignals.closeReason(null));
    }
}
