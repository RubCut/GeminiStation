package ru.geministation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherServiceTest {
    private static final String PLACES="{\"results\":[{\"id\":1,\"name\":\"Тест\",\"admin1\":\"Область\",\"country\":\"Россия\",\"latitude\":55,\"longitude\":37,\"timezone\":\"Europe/Moscow\"},{\"id\":2,\"name\":\"Тест\",\"admin1\":\"Другой регион\",\"country\":\"Франция\",\"latitude\":48,\"longitude\":2,\"timezone\":\"Europe/Paris\"}]}";
    private WeatherService service(MockWebServer server) {return new WeatherService(new OkHttpClient(),server.url("/search"),server.url("/forecast"));}
    private JSONObject forecast(String zone) throws Exception {
        ZoneId z=ZoneId.of(zone);String today=LocalDate.now(z).toString(),tomorrow=LocalDate.now(z).plusDays(1).toString();
        String now=LocalDateTime.now(z).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        return new JSONObject("{\"timezone\":\""+zone+"\",\"current\":{\"time\":\""+now+"\",\"temperature_2m\":12.3,\"apparent_temperature\":10,\"weather_code\":3,\"wind_speed_10m\":14},\"daily\":{\"time\":[\""+today+"\",\""+tomorrow+"\"],\"weather_code\":[3,61],\"temperature_2m_min\":[5,6],\"temperature_2m_max\":[15,16],\"precipitation_probability_max\":[20,80]}}");
    }
    @Test public void searchAndWeatherUseEncodedFixedEndpoints() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(PLACES));
            WeatherService svc=service(server); WeatherService.Place p=svc.search("Тест").get(0);
            assertEquals("Тест, Область, Россия",p.label());
            assertEquals("Тест", server.takeRequest().getRequestUrl().queryParameter("name"));
            server.enqueue(new MockResponse().setBody(forecast(p.timezone).toString()));
            assertTrue(svc.weather(p,0).contains("12 градусов"));
            assertEquals("55.0",server.takeRequest().getRequestUrl().queryParameter("latitude"));
            assertTrue(WeatherService.format(p,2,forecast(p.timezone)).contains("80 процентов"));
        }
    }
    @Test public void ambiguousPlacesAndNoDefaultAskInsteadOfGuessing() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            WeatherService svc=service(server);
            WeatherAnswer noDefault=WeatherAnswer.get(svc,null,"{\"city\":\"\",\"region\":\"\"}",0);
            assertTrue(noDefault.clarify); assertEquals(0,server.getRequestCount());
            server.enqueue(new MockResponse().setBody(PLACES));
            WeatherAnswer ambiguous=WeatherAnswer.get(svc,null,"{\"city\":\"Тест\",\"region\":\"\"}",0);
            assertTrue(ambiguous.clarify);assertTrue(ambiguous.text.contains("Франция"));assertEquals(1,server.getRequestCount());
            server.enqueue(new MockResponse().setBody(PLACES));server.enqueue(new MockResponse().setBody(forecast("Europe/Paris").toString()));
            WeatherAnswer resolved=WeatherAnswer.get(svc,null,"{\"city\":\"Тест\",\"region\":\"Франция\"}",1);
            assertFalse(resolved.clarify);assertTrue(resolved.text.contains("Франция"));
        }
    }
    @Test public void serviceFailuresStaleAndMalformedDataFailClosed() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            WeatherService svc=service(server);
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location",server.url("/elsewhere")));
            try{svc.search("Москва");fail();}catch(Exception expected){} assertEquals(1,server.getRequestCount());
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":4,\"name\":\"Bad\",\"latitude\":999,\"longitude\":1,\"timezone\":\"UTC\"}]}"));
            assertTrue(svc.search("Bad").isEmpty());
            WeatherService.Place p=new WeatherService.Place(1,"Тест","","","Europe/Moscow",55,37);
            JSONObject f=forecast(p.timezone); f.getJSONObject("current").put("time","2000-01-01T01:00");
            try{WeatherService.format(p,0,f);fail();}catch(Exception expected){}
            f=forecast(p.timezone);f.put("timezone","UTC");try{WeatherService.format(p,1,f);fail();}catch(Exception expected){}
            f=forecast(p.timezone);f.getJSONObject("daily").getJSONArray("temperature_2m_max").put(1,JSONObject.NULL);
            try{WeatherService.format(p,2,f);fail();}catch(Exception expected){}
        }
    }
    @Test public void toolArgumentsAndClockAreValidated() throws Exception {
        JSONObject call=new JSONObject().put("name","get_weather").put("args",new JSONObject().put("period","tomorrow"));
        assertEquals(2,DeviceToolSpec.parse(call,0).number);
        call.getJSONObject("args").put("period","next_week");assertNull(DeviceToolSpec.parse(call,0));
        call.getJSONObject("args").put("period","now").put("city","A\nB");assertNull(DeviceToolSpec.parse(call,0));
        call.put("name","get_datetime").put("args",new JSONObject().put("timezone","Fake/Zone"));assertNull(DeviceToolSpec.parse(call,0));
        call.getJSONObject("args").put("timezone","Europe/Moscow");assertNotNull(DeviceToolSpec.parse(call,0));
        assertTrue(ClockInfo.current("UTC",0).contains("00:00"));
        assertTrue(ClockInfo.current("Europe/Moscow",0).contains("03:00"));
        String prompt=GeminiToolProtocol.request(new org.json.JSONArray(),new org.json.JSONArray(),"Свой текст").toString();
        assertTrue(prompt.contains("get_datetime"));assertTrue(prompt.contains("get_weather"));
    }
}
