package ru.geministation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed Open-Meteo endpoints only. No location permissions, IP lookup, or free-form URL. */
final class WeatherService {
    private final OkHttpClient client;
    private final HttpUrl geocoding, forecast;
    WeatherService(OkHttpClient client) {
        this(client, HttpUrl.get("https://geocoding-api.open-meteo.com/v1/search"), HttpUrl.get("https://api.open-meteo.com/v1/forecast"));
    }
    WeatherService(OkHttpClient client, HttpUrl geocoding, HttpUrl forecast) {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build();
        this.geocoding = geocoding; this.forecast = forecast;
    }
    static boolean validQuery(String value) {
        return value != null && value.trim().length() >= 2 && value.trim().length() <= 80
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private JSONObject get(HttpUrl url) throws Exception {
        try (Response response = client.newCall(new Request.Builder().url(url).tag(String.class, "weather").build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Weather service unavailable");
            return new JSONObject(HttpLimits.readUtf8(response.body(), 256 * 1024));
        }
    }
    static final class Place {
        final long id; final String name, region, country, timezone; final double latitude, longitude;
        Place(long id, String name, String region, String country, String timezone, double latitude, double longitude) {
            this.id=id;this.name=name;this.region=region;this.country=country;this.timezone=timezone;this.latitude=latitude;this.longitude=longitude;
        }
        String label() { return name + (region.isEmpty() || region.equalsIgnoreCase(name) ? "" : ", " + region) + (country.isEmpty() ? "" : ", " + country); }
        JSONObject json() throws Exception { return new JSONObject().put("id",id).put("name",name).put("region",region).put("country",country).put("timezone",timezone).put("latitude",latitude).put("longitude",longitude); }
        static Place parse(JSONObject j) throws Exception {
            long id=j.getLong("id"); String name=safe(j.optString("name")), region=safe(j.optString("region")), country=safe(j.optString("country")), zone=j.optString("timezone");
            double lat=j.getDouble("latitude"), lon=j.getDouble("longitude");
            if (id <= 0 || name.isEmpty() || !Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon) || lon < -180 || lon > 180
                    || !java.util.Arrays.asList(TimeZone.getAvailableIDs()).contains(zone)) throw new IOException("Invalid location");
            return new Place(id,name,region,country,zone,lat,lon);
        }
        private static String safe(String s) throws IOException {
            if (s == null || s.length() > 120 || s.codePoints().anyMatch(Character::isISOControl)) throw new IOException("Invalid location text");
            return s.trim();
        }
    }
    List<Place> search(String query) throws Exception {
        if (!validQuery(query)) throw new IllegalArgumentException("Укажите город (от 2 до 80 символов)");
        HttpUrl url=geocoding.newBuilder().addQueryParameter("name",query.trim()).addQueryParameter("count","8")
                .addQueryParameter("language","ru").addQueryParameter("format","json").build();
        JSONArray rows=get(url).optJSONArray("results"); List<Place> places=new ArrayList<>();
        if (rows != null) for (int i=0;i<Math.min(rows.length(),8);i++) try {
            JSONObject r=rows.getJSONObject(i);
            JSONObject normalized=new JSONObject().put("id",r.getLong("id")).put("name",r.getString("name"))
                    .put("region",r.optString("admin1","")).put("country",r.optString("country", ""))
                    .put("timezone",r.getString("timezone")).put("latitude",r.getDouble("latitude")).put("longitude",r.getDouble("longitude"));
            Place p=Place.parse(normalized); if (places.stream().noneMatch(old -> old.id == p.id)) places.add(p);
        } catch (Exception ignored) { }
        return places;
    }
    String weather(Place place, int day) throws Exception {
        if (day < 0 || day > 2) throw new IllegalArgumentException("Invalid forecast period");
        HttpUrl url=forecast.newBuilder().addQueryParameter("latitude",Double.toString(place.latitude)).addQueryParameter("longitude",Double.toString(place.longitude))
                .addQueryParameter("current","temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
                .addQueryParameter("daily","weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                .addQueryParameter("forecast_days","2").addQueryParameter("timezone",place.timezone).build();
        return format(place,day,get(url));
    }
    private static String code(int n) {
        switch(n) {case 0:return "ясно"; case 1:case 2:return "переменная облачность"; case 3:return "пасмурно";
            case 45:case 48:return "туман"; case 51:case 53:case 55:case 56:case 57:return "морось";
            case 61:case 63:case 65:case 66:case 67:case 80:case 81:case 82:return "дождь";
            case 71:case 73:case 75:case 77:case 85:case 86:return "снег"; case 95:case 96:case 99:return "гроза";
            default:return "погодные условия неизвестны"; }
    }
    private static int finite(JSONObject j,String key) throws Exception {
        Object raw=j.opt(key); if (!(raw instanceof Number)) throw new IOException("Missing weather value");
        double d=((Number)raw).doubleValue(); if (!Double.isFinite(d) || Math.abs(d)>1000) throw new IOException("Invalid weather value");
        return (int)Math.round(d);
    }
    private static int at(JSONArray a,int i) throws Exception {
        if (a==null || i>=a.length() || !(a.opt(i) instanceof Number)) throw new IOException("Missing forecast value");
        double n=((Number)a.opt(i)).doubleValue(); if (!Double.isFinite(n) || Math.abs(n)>1000) throw new IOException("Invalid forecast value");
        return (int)Math.round(n);
    }
    static String format(Place p,int day,JSONObject json) throws Exception {
        if (!p.timezone.equals(json.optString("timezone"))) throw new IOException("Unexpected forecast timezone");
        java.time.ZoneId zone=java.time.ZoneId.of(p.timezone);
        if (day == 0) {
            JSONObject c=json.getJSONObject("current"); String stamp=c.getString("time");
            if (!stamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}")) throw new IOException("Invalid time");
            long measured=java.time.LocalDateTime.parse(stamp).atZone(zone).toInstant().toEpochMilli();
            if (Math.abs(System.currentTimeMillis() - measured) > TimeUnit.HOURS.toMillis(3)) throw new IOException("Stale weather data or incorrect station clock");
            return "Погода: " + p.label() + ". " + code(finite(c,"weather_code")) + ", температура " + finite(c,"temperature_2m")
                    + " градусов, ощущается как " + finite(c,"apparent_temperature") + ", ветер " + finite(c,"wind_speed_10m")
                    + " километров в час. Данные Open-Meteo на " + stamp.replace('T',' ') + ", местное время.";
        }
        JSONObject d=json.getJSONObject("daily"); int i=day-1;
        String date=d.getJSONArray("time").getString(i);
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IOException("Invalid date");
        if (!java.time.LocalDate.parse(date).equals(java.time.LocalDate.now(zone).plusDays(i))) throw new IOException("Stale forecast or incorrect station clock");
        int probability=at(d.optJSONArray("precipitation_probability_max"),i);
        if(probability<0 || probability>100) throw new IOException("Invalid precipitation");
        return "Прогноз для " + p.label() + " на " + date + ": " + code(at(d.optJSONArray("weather_code"),i))
                + ", от " + at(d.optJSONArray("temperature_2m_min"),i) + " до " + at(d.optJSONArray("temperature_2m_max"),i)
                + " градусов. Вероятность осадков до " + probability + " процентов. Источник: Open-Meteo.";
    }
}
