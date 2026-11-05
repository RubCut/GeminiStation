package ru.geministation;

import okhttp3.Request;
import org.junit.Test;
import static org.junit.Assert.*;

public class YandexMusicSearchTest {
    @Test public void searchUsesKnownReadOnlyClientFormatAndEncodesQuery() {
        String query = "Группа & песня + хит";
        Request request = YandexMusicSearch.request(query, "test-token");
        assertEquals("GET", request.method());
        assertEquals("https", request.url().scheme());
        assertEquals("api.music.yandex.net", request.url().host());
        assertEquals("/search", request.url().encodedPath());
        assertEquals(query, request.url().queryParameter("text"));
        assertEquals("track", request.url().queryParameter("type"));
        assertEquals("False", request.url().queryParameter("nocorrect"));
        assertEquals("0", request.url().queryParameter("page"));
        assertEquals("True", request.url().queryParameter("playlist-in-best"));
        assertEquals("OAuth test-token", request.header("Authorization"));
        assertFalse(request.url().toString().contains("test-token"));
        assertEquals("YandexMusicAndroid/24023621", request.header("X-Yandex-Music-Client"));
        assertEquals("Yandex-Music-API", request.header("User-Agent"));
        assertEquals("ru", request.header("Accept-Language"));
    }
    @Test public void doesNotSendBlankOrUnboundedSearch() {
        for (String query : new String[]{"", " ", new String(new char[161]).replace('\0', 'a'), "a\nb"}) {
            try { YandexMusicSearch.request(query, "token"); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
