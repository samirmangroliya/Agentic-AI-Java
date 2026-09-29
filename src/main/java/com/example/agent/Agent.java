package com.example.agent;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.AutomaticFunctionCallingConfig;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The agent loop: LLM decides -> we run tools -> results go back -> repeat. */
public final class Agent {
    private static final int MAX_STEPS = 10; // safety limit so the loop can't run forever

    private static final String SYSTEM_PROMPT =
            "You are a helpful planning assistant. Break the user's goal into steps and use "
                    + "tools when you need real data or calculations. If a tool returns an error, "
                    + "try a different approach. When you have enough information, give a clear "
                    + "final answer.";

    private final Client client;
    private final GenerateContentConfig config;
    private final String model;
    private final String fallbackModel;
    private final boolean verbose;

    public Agent() {
        String apiKey = Env.get("API_KEY", Env.get("GEMINI_API_KEY", Env.get("GOOGLE_API_KEY", "")));
        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                    "API key not found. Put API_KEY=... in a .env file in the project folder.");
        }
        this.model = Env.get("LLM_MODEL", "gemini-flash-latest");
        this.fallbackModel = Env.get("LLM_FALLBACK_MODEL", "gemini-flash-lite-latest");
        this.verbose = Env.get("LLM_VERBOSE", "false").equalsIgnoreCase("true");
        this.client = Client.builder().apiKey(apiKey).build();
        this.config =
                GenerateContentConfig.builder()
                        .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_PROMPT)))
                        .tools(Tools.declarations())
                        // We run the loop ourselves so you can see every step
                        .automaticFunctionCalling(
                                AutomaticFunctionCallingConfig.builder().disable(true).build())
                        .build();
    }

    /** Memory = the list of messages (Gemini calls them "contents"). */
    public List<Content> newConversation() {
        return new ArrayList<>();
    }

    /** Runs the agent until it gives a final answer. {@code messages} is memory, updated in place. */
    public String run(String userGoal, List<Content> messages) {
        messages.add(Content.builder().role("user").parts(List.of(Part.fromText(userGoal))).build());

        for (int step = 1; step <= MAX_STEPS; step++) {
            GenerateContentResponse response;
            try {
                response = callModel(messages);
            } catch (ApiException e) {
                return friendlyError(e);
            }

            Optional<Content> modelTurn =
                    response.candidates()
                            .flatMap(list -> list.stream().findFirst())
                            .flatMap(Candidate::content);
            if (modelTurn.isEmpty()) {
                return "The model returned an empty response. Try rephrasing.";
            }

            // Keep the model's turn exactly as returned (Gemini 3 needs this for tool calls)
            messages.add(modelTurn.get());

            List<FunctionCall> calls = response.functionCalls();
            if (calls == null || calls.isEmpty()) { // no tool calls -> the model is finished
                String text = response.text();
                return (text == null || text.isBlank()) ? "(no text returned)" : text;
            }

            // Run every requested tool, then send all results back in ONE user message
            List<Part> resultParts = new ArrayList<>();
            for (FunctionCall call : calls) {
                String name = call.name().orElse("");
                Map<String, Object> args = call.args().orElse(Map.of());
                System.out.println("  [step " + step + "] " + name + "(" + args + ")");

                Map<String, Object> result = Tools.execute(name, args);
                resultParts.add(Part.fromFunctionResponse(name, result));
            }
            messages.add(Content.builder().role("user").parts(resultParts).build());
        }
        return "Stopped: reached the maximum number of steps.";
    }

    /** Retry temporary server errors with backoff, then try the fallback model. */
    private GenerateContentResponse callModel(List<Content> messages) {
        final int maxAttempts = 4;
        ApiException lastError = null;
        String[] models = {model, fallbackModel};

        for (int m = 0; m < models.length; m++) {
            String candidateModel = models[m];
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (verbose) {
                    System.out.println(
                            "  [" + candidateModel + "] attempt " + attempt + "/" + maxAttempts + "...");
                }
                try {
                    GenerateContentResponse response =
                            client.models.generateContent(candidateModel, messages, config);
                    if (m > 0) {
                        System.out.println("  [" + candidateModel + "] fallback model answered OK");
                    } else if (verbose && attempt > 1) {
                        System.out.println("  [" + candidateModel + "] OK after " + attempt + " attempts");
                    }
                    return response;
                } catch (ApiException e) {
                    lastError = e;
                    if (!isTemporary(e)) {
                        throw e; // bad key, bad model, quota... retrying won't help
                    }
                    if (attempt == maxAttempts) {
                        System.out.println(
                                "  [" + candidateModel + "] " + e.code() + " " + shortMessage(e)
                                        + " - giving up on this model");
                        break;
                    }
                    long waitSeconds = 1L << (attempt - 1); // 1s, 2s, 4s
                    System.out.println(
                            "  [" + candidateModel + "] " + e.code() + " " + shortMessage(e)
                                    + " - busy, retrying in " + waitSeconds + "s (attempt "
                                    + attempt + "/" + maxAttempts + ")...");
                    sleep(waitSeconds * 1000);
                }
            }
            if (m < models.length - 1) {
                System.out.println("  Trying fallback model: " + models[m + 1]);
            }
        }
        throw lastError;
    }

    private static String shortMessage(ApiException e) {
        String msg = String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
        return msg.length() > 80 ? msg.substring(0, 80) + "..." : msg;
    }

    private static boolean isTemporary(ApiException e) {
        int code = e.code();
        return code == 500 || code == 503 || code == 504;
    }

    private String friendlyError(ApiException e) {
        int code = e.code();
        if (code == 429) {
            return "Rate limit or quota reached. Wait a minute and try again.";
        }
        if (code == 404) {
            return "Model '" + model + "' not found. Change LLM_MODEL in .env to a current model name.";
        }
        if (code == 401 || code == 403 || code == 400) {
            return "Request rejected (" + code + "): " + e.getMessage()
                    + "\nCheck that API_KEY in .env is correct.";
        }
        if (isTemporary(e)) {
            return "Gemini is overloaded right now (even after retries). Try again in a few minutes.";
        }
        return "API error " + code + ": " + e.getMessage();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
