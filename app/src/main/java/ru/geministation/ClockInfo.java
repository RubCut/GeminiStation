package ru.geministation;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Station clock, not an online time authority. */
final class ClockInfo {
    static boolean validZone(String id) {
        return id != null && (id.equals("UTC") || java.util.Arrays.asList(TimeZone.getAvailableIDs()).contains(id));
    }
    static String current(String zoneId, long now) {
        TimeZone zone=zoneId == null || zoneId.isEmpty() ? TimeZone.getDefault() : TimeZone.getTimeZone(zoneId);
        SimpleDateFormat f=new SimpleDateFormat("HH:mm, d MMMM yyyy, EEEE",new Locale("ru","RU")); f.setTimeZone(zone);
        return "Сейчас " + f.format(new Date(now)) + ". Часовой пояс: " + zone.getID() + ". Время по часам станции.";
    }
    private ClockInfo() { }
}
