package ru.geministation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MusicTypesTest {
    @Test public void parsesOnlyUsableAlbumAndPlaylistIdentifiers() throws Exception {
        JSONObject root = new JSONObject("{\"albums\":{\"results\":[{\"id\":\"not-an-id\",\"title\":\"bad\"},{\"id\":\"42\",\"title\":\"Альбом\",\"artists\":[{\"name\":\"Автор\"}]}]},\"playlists\":{\"results\":[{\"kind\":17,\"title\":\"bad\"},{\"owner\":{\"uid\":123},\"kind\":99,\"title\":\"Плейлист\"}]}}");
        YandexCloud.Track album = YandexCloud.parseSearch(root, "album"), playlist = YandexCloud.parseSearch(root, "playlist");
        assertEquals("42", album.id); assertEquals("Автор — Альбом", album.title); assertEquals("album", album.type);
        assertEquals("123:99", playlist.id); assertEquals("Плейлист", playlist.title);
        assertEquals("playlist", YandexMusicSearch.request("тест", "token", "playlist").url().queryParameter("type"));
        try { YandexCloud.parseSearch(new JSONObject("{\"playlists\":{\"results\":[{\"kind\":3,\"title\":\"No owner\"}]}}"), "playlist"); fail(); } catch (java.io.IOException expected) { }
    }
    @Test public void musicToolKindIsAllowlisted() throws Exception {
        JSONObject args = new JSONObject().put("query", "тест").put("kind", "album");
        JSONObject call = new JSONObject().put("name", "yandex_music").put("args", args);
        assertEquals(1, DeviceToolSpec.parse(call, 0).number);
        args.put("kind", "artist"); assertNull(DeviceToolSpec.parse(call, 0));
    }
}
