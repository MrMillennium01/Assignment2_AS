import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HttpExample {

    static final Gson DECODER = new Gson();

    static class Response {
        int userId;
        int id;
        String title;
        boolean completed;
    }

    static void main() {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> res = null;
        HttpRequest req = null;

        try {
            req = HttpRequest.newBuilder()
                    .uri(new URI("https://jsonplaceholder.typicode.com/todos/1"))
                    .GET()
                    .build();
        } catch (URISyntaxException e) {
            IO.println(e);
            System.exit(1);
        }

        try {
            res = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            IO.println(e.getMessage());
        }

        Response responseObject = DECODER.fromJson(res.body(), Response.class);
        IO.println(responseObject.title);
    }
}
