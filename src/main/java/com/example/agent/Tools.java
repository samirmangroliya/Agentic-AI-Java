package com.example.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tools the agent can call: each tool = a Java method + a function declaration for the LLM. */
public final class Tools {
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private Tools() {}

    // ------------------------------------------------------------ tool functions

    /** Current weather + 3-day forecast using Open-Meteo (no API key needed). */
    static Map<String, Object> getWeather(String city) throws IOException, InterruptedException {
        String geoUrl =
                "https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                        + URLEncoder.encode(city, StandardCharsets.UTF_8);
        Map<String, Object> geo = getJson(geoUrl);

        Object resultsObj = geo.get("results");
        if (!(resultsObj instanceof List<?> results) || results.isEmpty()) {
            return Map.of("error", "Could not find city '" + city + "'");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> place = (Map<String, Object>) results.get(0);

        String forecastUrl =
                "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + place.get("latitude")
                        + "&longitude=" + place.get("longitude")
                        + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m"
                        + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=3";
        Map<String, Object> forecast = getJson(forecastUrl);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("city", place.get("name"));
        out.put("country", place.get("country"));
        out.put("current", forecast.get("current"));
        out.put("daily", forecast.get("daily"));
        return out;
    }

    /** Evaluate a basic math expression such as "3 * 2500 + 1200". */
    static Map<String, Object> calculator(String expression) {
        try {
            double value = new ExpressionParser(expression).parse();
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return Map.of("result", (long) value);
            }
            return Map.of("result", value);
        } catch (RuntimeException e) {
            return Map.of("error", "Could not evaluate '" + expression + "': " + e.getMessage());
        }
    }

    /** Save text into the notes/ folder. */
    static Map<String, Object> saveNote(String filename, String content) throws IOException {
        String safe = filename.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) {
            safe = "note";
        }
        Path dir = Path.of("notes");
        Files.createDirectories(dir);
        Path file = dir.resolve(safe + ".txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return Map.of("saved_to", file.toString());
    }

    // ------------------------------------------------------------ dispatcher

    /** Runs a tool by name. Errors are returned as {"error": ...} so the LLM can adapt. */
    public static Map<String, Object> execute(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "get_weather" -> getWeather(str(args, "city"));
                case "calculator" -> calculator(str(args, "expression"));
                case "save_note" -> saveNote(str(args, "filename"), str(args, "content"));
                default -> Map.of("error", "Unknown tool: " + name);
            };
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> getJson(String url) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return JSON.readValue(response.body(), MAP_TYPE);
    }

    // ------------------------------------------------------------ declarations (what the LLM sees)

    /** Good descriptions = better tool choices. */
    public static List<Tool> declarations() {
        FunctionDeclaration weather =
                FunctionDeclaration.builder()
                        .name("get_weather")
                        .description("Get current weather and a 3-day forecast for a city.")
                        .parameters(
                                Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(
                                                Map.of(
                                                        "city",
                                                        Schema.builder()
                                                                .type(Type.Known.STRING)
                                                                .description("City name, e.g. 'Manali'")
                                                                .build()))
                                        .required("city")
                                        .build())
                        .build();

        FunctionDeclaration calc =
                FunctionDeclaration.builder()
                        .name("calculator")
                        .description(
                                "Evaluate a math expression. Use for any arithmetic, such as budgets.")
                        .parameters(
                                Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(
                                                Map.of(
                                                        "expression",
                                                        Schema.builder()
                                                                .type(Type.Known.STRING)
                                                                .description("e.g. '6000 / 2'")
                                                                .build()))
                                        .required("expression")
                                        .build())
                        .build();

        FunctionDeclaration note =
                FunctionDeclaration.builder()
                        .name("save_note")
                        .description("Save text to a file in the notes folder so the user can keep it.")
                        .parameters(
                                Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(
                                                Map.of(
                                                        "filename",
                                                        Schema.builder()
                                                                .type(Type.Known.STRING)
                                                                .description("Name without extension")
                                                                .build(),
                                                        "content",
                                                        Schema.builder()
                                                                .type(Type.Known.STRING)
                                                                .build()))
                                        .required("filename", "content")
                                        .build())
                        .build();

        return List.of(Tool.builder().functionDeclarations(weather, calc, note).build());
    }

    // ------------------------------------------------------------ safe calculator (no eval in Java)

    /** Recursive-descent parser: numbers, + - * / %, parentheses, unary minus. */
    private static final class ExpressionParser {
        private final String text;
        private int pos = 0;

        ExpressionParser(String text) {
            this.text = text.replace(",", "").replace("₹", "").replace("$", "");
        }

        double parse() {
            double value = parseExpression();
            skipSpaces();
            if (pos < text.length()) {
                throw new IllegalArgumentException("unexpected '" + text.charAt(pos) + "'");
            }
            return value;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipSpaces();
                if (match('+')) {
                    value += parseTerm();
                } else if (match('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipSpaces();
                if (match('*')) {
                    value *= parseFactor();
                } else if (match('/')) {
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value /= divisor;
                } else if (match('%')) {
                    value %= parseFactor();
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipSpaces();
            if (match('-')) {
                return -parseFactor();
            }
            if (match('+')) {
                return parseFactor();
            }
            if (match('(')) {
                double value = parseExpression();
                skipSpaces();
                if (!match(')')) {
                    throw new IllegalArgumentException("missing ')'");
                }
                return value;
            }
            int start = pos;
            while (pos < text.length()
                    && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("expected a number at position " + pos);
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        private boolean match(char c) {
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
