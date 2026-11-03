package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalWakeMatcherTest {
    private float[][] sequence(int n, int seed) {
        java.util.Random random = new java.util.Random(seed);
        float[][] x = new float[n][20];
        for (int i = 0; i < n; i++) for (int j = 0; j < 20; j++) x[i][j] = (float) random.nextGaussian();
        return x;
    }
    @Test public void findsMatchingSubsequenceButRejectsUnrelatedSound() {
        float[][] ref = sequence(20, 42), audio = sequence(60, 13);
        for (int i = 0; i < ref.length; i++) audio[25 + i] = ref[i].clone();
        assertEquals(0, PersonalWakeMatcher.distance(ref, audio), .00001);
        assertTrue(PersonalWakeMatcher.distance(ref, sequence(60, 99)) > PersonalWakeMatcher.CUTOFF);
    }
    @Test public void rejectsUnusableLengthsAndNormalizesSilence() {
        assertTrue(Float.isInfinite(PersonalWakeMatcher.distance(sequence(9, 1), sequence(30, 2))));
        for (float x : PersonalWakeMatcher.normalize(new float[40])) assertEquals(0, x, 0);
    }
    @Test public void historyIsBoundedAndCopiesFrames() {
        java.util.List<float[][]> templates = new java.util.ArrayList<>(); templates.add(sequence(20, 1));
        PersonalWakeMatcher matcher = new PersonalWakeMatcher(templates);
        float[] frame = new float[40]; frame[0] = 10;
        for (int i = 0; i < 300; i++) matcher.add(frame);
        float[][] copy = matcher.snapshot(); assertEquals(80, copy.length);
        copy[0][0] = 12345; assertNotEquals(12345, matcher.snapshot()[0][0], 0);
    }
}
