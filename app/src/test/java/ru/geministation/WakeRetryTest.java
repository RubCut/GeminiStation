package ru.geministation;
import org.junit.Test;
import static org.junit.Assert.*;

public class WakeRetryTest {
    @Test public void backoffIsBoundedAndStableCaptureResetsIt() {
        WakeRetry retry = new WakeRetry();
        assertEquals(3000, retry.afterFailure(1));
        assertEquals(6000, retry.afterFailure(1));
        assertEquals(12000, retry.afterFailure(1));
        assertEquals(-1, retry.afterFailure(1));
        assertEquals(3000, retry.afterFailure(60000));
        retry.reset();
        assertEquals(3000, retry.afterFailure(1));
    }
}
