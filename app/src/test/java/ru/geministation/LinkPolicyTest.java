package ru.geministation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LinkPolicyTest {
    @Test public void onlyHttpAndHttpsWithoutUserInfo() {
        assertTrue(LinkPolicy.allowed("https://youtube.com/watch?v=abcdefghijk"));
        assertTrue(LinkPolicy.allowed("http://example.com/path"));
        for (String url : new String[]{"intent://x", "javascript:alert(1)", "file:///data/x", "https://alice@example.com/x", "https://", "http://example.com/a b"})
            assertFalse(url, LinkPolicy.allowed(url));
    }
    @Test public void modelGeneratedLinksAreNotTreatedAsUserAuthorization() throws Exception {
        String url = "https://example.com/watch";
        JSONArray history = new JSONArray()
                .put(new JSONObject().put("role", "model").put("parts", new JSONArray().put(new JSONObject().put("text", url))))
                .put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", "Открой ссылку"))));
        assertFalse(UserLinkAuthorization.presentInUserTurns(url, history));
        history.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", "Открой " + url))));
        assertTrue(UserLinkAuthorization.presentInUserTurns(url, history));
    }
    @Test public void userLinkMustBeAWholeUrlNotASubstring() throws Exception {
        String url = "https://example.com";
        assertFalse(UserLinkAuthorization.containsWholeUrl("https://example.com.evil/path", url));
        assertFalse(UserLinkAuthorization.containsWholeUrl("https://other.test/?redirect=https://example.com", url));
        assertFalse(UserLinkAuthorization.containsWholeUrl("https://example.com/private", url));
        assertFalse(UserLinkAuthorization.containsWholeUrl("https://other.test/(https://example.com)", url));
        assertFalse(UserLinkAuthorization.containsWholeUrl("https://example.com)private", url));
        assertTrue(UserLinkAuthorization.containsWholeUrl("Открой (https://example.com)", url));
        assertTrue(UserLinkAuthorization.containsWholeUrl("Открой https://example.com сейчас", url));
    }
    @Test public void onlyBrowsableViewFiltersProduceWebHints() {
        LinkHintFilter filter = new LinkHintFilter();
        filter.data("https", "*.youtube.com"); filter.data("intent", "ignored");
        assertTrue(filter.hints().isEmpty());
        filter.view = filter.browsable = filter.defaultCategory = true;
        assertTrue(filter.hints().contains("https://*.youtube.com"));
        assertFalse(filter.hints().contains("intent://ignored"));
        LinkHintFilter browser = new LinkHintFilter();
        browser.view = browser.browsable = browser.defaultCategory = true;
        browser.data("http", null);
        assertTrue(browser.hints().contains("http://*"));
    }
}
