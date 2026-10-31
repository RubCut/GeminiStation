package ru.geministation;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DialogDirectiveTest {
    @Test public void textMarkersAreHiddenAndMissingDefaultsToContinue() {
        assertEquals("До встречи!", DialogDirective.text("До встречи! [[DIALOG_END]]").text);
        assertTrue(DialogDirective.text("До встречи! [[DIALOG_END]]").finish);
        assertFalse(DialogDirective.text("Ещё вопрос? [[DIALOG_CONTINUE]]").finish);
        assertFalse(DialogDirective.text("Нет маркера").finish);
        assertFalse(DialogDirective.text("[[DIALOG_END]] не последний").finish);
        assertFalse(DialogDirective.text("[[DIALOG_END]] не последний").text.contains("[[DIALOG_END]]"));
    }
    @Test public void onlyExactValidToolDirectiveEnds() throws Exception {
        assertTrue(DialogDirective.toolFinishes(new JSONObject().put("args", new JSONObject().put("dialog", "end"))));
        assertFalse(DialogDirective.toolFinishes(new JSONObject().put("args", new JSONObject().put("dialog", "continue"))));
        assertFalse(DialogDirective.toolFinishes(new JSONObject().put("args", new JSONObject().put("dialog", true))));
        assertFalse(DialogDirective.toolFinishes(new JSONObject()));
        assertFalse(DialogDirective.toolFinishes(null));
    }
}
