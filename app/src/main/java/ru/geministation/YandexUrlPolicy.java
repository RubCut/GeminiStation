package ru.geministation;

import java.net.URI;
import java.util.Locale;

/** Redirects and QR links must stay on HTTPS services operated by Yandex. */
final class YandexUrlPolicy {
    static boolean allowed(String raw) {
        try {
            URI u = new URI(raw);
            String host = u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || host == null || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String domain : new String[]{"yandex.ru", "yandex.kz", "yandex.net", "ya.ru"})
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
        } catch (Exception ignored) { }
        return false;
    }
    private YandexUrlPolicy() { }
}
