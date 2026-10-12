import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class WeatherApplication {
    private static final String API_KEY = System.getenv("OPENWEATHER_API_KEY");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Simple Weather</title>
              <style>
                * { box-sizing: border-box; }
                body {
                  margin: 0; min-height: 100vh; display: grid; place-items: center;
                  padding: 24px; font-family: system-ui, sans-serif; color: #102a43;
                  background: linear-gradient(140deg, #d9f3ff, #edf2ff);
                }
                main {
                  width: min(100%, 440px); padding: 32px; border-radius: 22px;
                  background: white; box-shadow: 0 18px 50px #183b561c;
                }
                h1 { margin: 0 0 8px; }
                p { color: #52677b; }
                form { display: flex; gap: 8px; margin: 24px 0; }
                input, button {
                  min-width: 0; padding: 12px 14px; border: 1px solid #cbd5e1;
                  border-radius: 10px; font: inherit;
                }
                input { flex: 1; }
                button { border: 0; color: white; background: #1677c8; cursor: pointer; }
                button:hover { background: #105f9f; }
                #result { text-align: center; }
                #icon { width: 90px; height: 90px; }
                #temperature { margin: 0; font-size: 3rem; font-weight: 700; }
                #details { line-height: 1.8; }
                #message { min-height: 1.5em; color: #9b2c2c; }
              </style>
            </head>
            <body>
              <main>
                <h1>Weather</h1>
                <p>Check the current weather in any city.</p>
                <form id="search">
                  <input id="city" name="city" placeholder="Enter a city" required maxlength="100">
                  <button type="submit">Search</button>
                </form>
                <p id="message" role="status"></p>
                <section id="result" hidden>
                  <h2 id="place"></h2>
                  <img id="icon" alt="">
                  <p id="temperature"></p>
                  <p id="description"></p>
                  <p id="details"></p>
                </section>
              </main>
              <script>
                const form = document.querySelector("#search");
                const message = document.querySelector("#message");
                const result = document.querySelector("#result");

                form.addEventListener("submit", async (event) => {
                  event.preventDefault();
                  const city = document.querySelector("#city").value.trim();
                  if (!city) return;
                  message.textContent = "Loading weather…";
                  result.hidden = true;
                  try {
                    const response = await fetch(`/api/weather?city=${encodeURIComponent(city)}`);
                    const weather = await response.json();
                    if (!response.ok) throw new Error(weather.message || "Weather lookup failed.");

                    const current = weather.weather[0];
                    document.querySelector("#place").textContent = `${weather.name}, ${weather.sys.country}`;
                    document.querySelector("#icon").src =
                      `https://openweathermap.org/img/wn/${encodeURIComponent(current.icon)}@2x.png`;
                    document.querySelector("#icon").alt = current.description;
                    document.querySelector("#temperature").textContent = `${Math.round(weather.main.temp)}°C`;
                    document.querySelector("#description").textContent = current.description;
                    document.querySelector("#details").textContent =
                      `Feels like ${Math.round(weather.main.feels_like)}°C · Humidity ${weather.main.humidity}% · Wind ${weather.wind.speed} m/s`;
                    result.hidden = false;
                    message.textContent = "";
                  } catch (error) {
                    message.textContent = error.message;
                  }
                });
              </script>
            </body>
            </html>
            """;

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            } else if (exchange.getRequestURI().getPath().equals("/")) {
                respond(exchange, 200, "text/html; charset=utf-8", PAGE);
            } else {
                respond(exchange, 404, "text/plain; charset=utf-8", "Not found");
            }
        });
        server.createContext("/api/weather", WeatherApplication::getWeather);
        server.start();
        System.out.println("Weather app running at http://localhost:" + port);
    }

    private static void getWeather(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, "application/json; charset=utf-8", "{\"message\":\"Method not allowed\"}");
            return;
        }
        if (API_KEY == null || API_KEY.isBlank()) {
            respond(exchange, 500, "application/json; charset=utf-8",
                    "{\"message\":\"Set the OPENWEATHER_API_KEY environment variable first.\"}");
            return;
        }

        String city = queryParameters(exchange.getRequestURI()).get("city");
        if (city == null || city.isBlank() || city.length() > 100) {
            respond(exchange, 400, "application/json; charset=utf-8",
                    "{\"message\":\"Enter a city name (up to 100 characters).\"}");
            return;
        }

        String encodedCity = URLEncoder.encode(city.trim(), StandardCharsets.UTF_8);
        String encodedKey = URLEncoder.encode(API_KEY, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.openweathermap.org/data/2.5/weather?q="
                + encodedCity + "&appid=" + encodedKey + "&units=metric");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            respond(exchange, response.statusCode(), "application/json; charset=utf-8", response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, "application/json; charset=utf-8",
                    "{\"message\":\"Weather request was interrupted. Please try again.\"}");
        } catch (IOException exception) {
            respond(exchange, 502, "application/json; charset=utf-8",
                    "{\"message\":\"Could not reach the weather service. Please try again.\"}");
        }
    }

    private static Map<String, String> queryParameters(URI uri) {
        Map<String, String> parameters = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return parameters;

        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            parameters.put(
                    URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
