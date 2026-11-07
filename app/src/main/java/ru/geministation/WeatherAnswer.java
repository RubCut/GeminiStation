package ru.geministation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Resolve explicit cities conservatively; never silently substitute the saved place. */
final class WeatherAnswer {
    final String text; final boolean clarify;
    WeatherAnswer(String text,boolean clarify) {this.text=text;this.clarify=clarify;}
    static WeatherAnswer get(WeatherService service, WeatherService.Place selected, String data, int day) throws Exception {
        JSONObject args=new JSONObject(data); String city=args.getString("city"), region=args.getString("region");
        WeatherService.Place place;
        if(city.isEmpty()) {
            place=selected;
            if(place==null)return new WeatherAnswer("Для какого города нужна погода? Можно также выбрать город по умолчанию в веб-панели.",true);
        } else {
            List<WeatherService.Place> found=service.search(city), matching=new ArrayList<>();
            String needle=region.toLowerCase(Locale.ROOT);
            for(WeatherService.Place p:found) if(needle.isEmpty() || p.region.toLowerCase(Locale.ROOT).contains(needle) || p.country.toLowerCase(Locale.ROOT).contains(needle)) matching.add(p);
            if(matching.isEmpty())return new WeatherAnswer("Не удалось найти указанный город. Уточните название и регион.",true);
            if(matching.size()>1) {
                StringBuilder labels=new StringBuilder();
                for(int i=0;i<Math.min(matching.size(),3);i++){if(i>0)labels.append("; ");labels.append(matching.get(i).label());}
                return new WeatherAnswer("Найдено несколько мест: " + labels + ". Уточните регион или страну.",true);
            }
            place=matching.get(0);
        }
        return new WeatherAnswer(service.weather(place,day),false);
    }
    private WeatherAnswer() { text=null; clarify=false; }
}
