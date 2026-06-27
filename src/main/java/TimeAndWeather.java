import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;


public class TimeAndWeather {
    private static final String apiKey = "tNt4dUMSzTfSYm8wDIH3mnr3WViVonZsUOtGVIh1";
    private static final Scanner scanner = new Scanner(System.in);
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception{
        System.out.println("Welcome to time&weather!");

        while (true){
            ArrayList<Double> coordinates = getGeoData();

            Timezone timezone = getTime(coordinates);
            Weather weather = getWeather(coordinates);

            System.out.println("It is currently " + timezone.day_of_week + ", " + timezone.local_time +
                    " and the temperature is " + weather.temp + ".");

            System.out.println("Do you want to continue? (Y/n)");
            String yN = scanner.nextLine();

            if (yN.equals("n")) break;
        }

        System.out.println("Thank you for using time&weather :)");
    }

    static ArrayList<Double> getGeoData() throws IOException, InterruptedException {
        while (true) {
            System.out.println("Please type in the City (use ae for ä, oe for ö and ue" +
                    " for ü and dash instead of space if the city-name is separated):");
            String city = scanner.nextLine();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.api-ninjas.com/v1/geocoding?city=" + city))
                    .header("X-Api-Key", apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            Type listType = new TypeToken<List<Location>>() {}.getType();
            List<Location> results = gson.fromJson(response.body(), listType);

            if (results == null || results.isEmpty()) {
                System.out.println("City not found. Please try again.");
                continue; // ← loops back to the top
            }

            Location first = results.get(0);
            double lat = first.latitude;
            double lon = first.longitude;

            ArrayList<Double> coordinates = new ArrayList<>();
            coordinates.add(lat);
            coordinates.add(lon);

            return coordinates; // ← only reached if a result was found
        }
    }

    static Timezone getTime(ArrayList<Double> coordinates) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://timeapi.io/api/v1/timezone/coordinate?latitude="+coordinates.get(0)+"&longitude="+coordinates.get(1)))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        return gson.fromJson(response.body(), Timezone.class);
    }

    static Weather getWeather(ArrayList<Double> coordinates) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.api-ninjas.com/v1/weather?lat="+coordinates.get(0)+"&lon="+coordinates.get(1)))
                .header("X-Api-Key", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        return gson.fromJson(response.body(), Weather.class);
    }
}

class Location {
    String name;
    double latitude;
    double longitude;
    String country;
    String state;
}

class Timezone {
    String timezone;
    int current_utc_offset_seconds;
    int standard_utc_offset_seconds;
    int dst_utc_offset_seconds;
    boolean has_dst;
    int dst_offset_seconds;
    boolean dst_active;
    String dst_from;
    String dst_until;
    String local_time;
    String day_of_week;
    String utc_time;
    long unix_timestamp;
}

class Weather {
    int cloud_pct;
    int temp;
    int feels_like;
    int humidity;
    int min_temp;
    int max_temp;
    double wind_speed;
    int wind_degrees;
    long sunrise;
    long sunset;
}
