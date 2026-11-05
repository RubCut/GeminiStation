package ru.geministation;

import org.junit.Test;
import static org.junit.Assert.*;

public class YandexUrlPolicyTest {
    @Test public void permitsOnlySecureYandexDomains() {
        assertTrue(YandexUrlPolicy.allowed("https://passport.yandex.ru/pwl-yandex"));
        assertTrue(YandexUrlPolicy.allowed("https://ya.ru/qr?id=123"));
        assertFalse(YandexUrlPolicy.allowed("http://passport.yandex.ru/"));
        assertFalse(YandexUrlPolicy.allowed("https://yandex.ru.evil.example/"));
        assertFalse(YandexUrlPolicy.allowed("https://evil-yandex.ru/"));
        assertFalse(YandexUrlPolicy.allowed("https://yandex.ru@evil.example/"));
        assertFalse(YandexUrlPolicy.allowed("https://passport.yandex.ru:444/"));
        assertFalse(YandexUrlPolicy.allowed("file:///etc/passwd"));
    }
}
