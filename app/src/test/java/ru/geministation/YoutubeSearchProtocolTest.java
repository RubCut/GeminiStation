package ru.geministation;

import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class YoutubeSearchProtocolTest {
    @Test public void onlyValidReturnedVideoIdsBecomeVideoLinks() throws Exception {
        JSONObject data = new JSONObject().put("items", new JSONArray().put(new JSONObject().put("id", new JSONObject().put("videoId", "abc123_-XYZ"))));
        assertEquals("https://www.youtube.com/watch?v=abc123_-XYZ", YoutubeSearchProtocol.videoUrl(data));
        for (String id : new String[]{"", "../redirect", "https://example.com", "abc"}) {
            JSONObject bad = new JSONObject().put("items", new JSONArray().put(new JSONObject().put("id", new JSONObject().put("videoId", id))));
            try { YoutubeSearchProtocol.videoUrl(bad); fail("must reject " + id); } catch (java.io.IOException expected) { }
        }
    }
    @Test public void fallbackIsAResultsPageWithAnEncodedQuery() {
        String url = YoutubeSearchProtocol.resultsUrl("котики & собаки");
        HttpUrl parsed = HttpUrl.get(url);
        assertEquals("www.youtube.com", parsed.host());
        assertEquals("/results", parsed.encodedPath());
        assertEquals("котики & собаки", parsed.queryParameter("search_query"));
    }
}
