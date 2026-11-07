package ru.geministation;

import android.content.Context;
import org.json.JSONObject;

/** Default location is chosen explicitly; it is not a credential or part of settings export. */
final class WeatherSettings {
    static WeatherService.Place get(Context context) {
        String value=context.getSharedPreferences("weather",Context.MODE_PRIVATE).getString("location","");
        if(value.isEmpty())return null;
        try{return WeatherService.Place.parse(new JSONObject(value));}catch(Exception e){return null;}
    }
    static boolean save(Context context,WeatherService.Place place) throws Exception {
        return context.getSharedPreferences("weather",Context.MODE_PRIVATE).edit().putString("location",place.json().toString()).commit();
    }
    static boolean clear(Context context) {return context.getSharedPreferences("weather",Context.MODE_PRIVATE).edit().remove("location").commit();}
    private WeatherSettings() { }
}
