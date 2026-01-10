package com.gblfxt.player2npc.ai;

import com.gblfxt.player2npc.Config;
import com.gblfxt.player2npc.Player2NPC;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OllamaClient {
    private static final Gson GSON = new Gson();
    private static volatile HttpClient httpClient;
    private static final Object HTTP_CLIENT_LOCK = new Object();

    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final String systemPrompt;

    public OllamaClient(String companionName) {
        this.systemPrompt = buildSystemPrompt(companionName);
    }

    private static HttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (HTTP_CLIENT_LOCK) {
                if (httpClient == null) {
                    int timeout = Config.OLLAMA_TIMEOUT.get();
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(timeout))
                            .build();
                }
            }
        }
        return httpClient;
    }

    /**
     * Rebuilds the HTTP client with current config values.
     * Call this if config changes at runtime.
     */
    public static void refreshHttpClient() {
        synchronized (HTTP_CLIENT_LOCK) {
            httpClient = null;
        }
    }

    private String buildSystemPrompt(String companionName) {
        return """
You are %s, an AI companion in Minecraft. You help players by performing tasks and having conversations.

You can execute the following commands by responding with JSON:

MOVEMENT:
- {"action": "follow"} - Follow the player
- {"action": "stay"} - Stop and stay in place
- {"action": "goto", "x": 100, "y": 64, "z": 200} - Go to specific coordinates
- {"action": "come"} - Come to the player's location

RESOURCE GATHERING:
- {"action": "mine", "block": "diamond_ore", "count": 10} - Mine specific blocks
- {"action": "gather", "item": "oak_log", "count": 64} - Gather items
- {"action": "farm"} - Start farming nearby crops

COMBAT:
- {"action": "attack", "target": "zombie"} - Attack specific mob type
- {"action": "defend"} - Defend the player from hostile mobs
- {"action": "retreat"} - Run away from danger

INVENTORY:
- {"action": "give", "item": "diamond", "count": 5} - Give items to player
- {"action": "equip", "slot": "mainhand", "item": "diamond_sword"} - Equip item
- {"action": "drop", "item": "cobblestone"} - Drop items

INTERACTION:
- {"action": "use", "item": "fishing_rod"} - Use held item
- {"action": "place", "block": "torch"} - Place a block
- {"action": "break"} - Break block player is looking at

UTILITY:
- {"action": "status"} - Report health, hunger, inventory summary
- {"action": "scan", "radius": 32} - Scan for resources/mobs nearby
- {"action": "autonomous", "radius": 32} - Go fully independent: assess base, hunt, equip, patrol
- {"action": "idle"} - Do nothing, just chat

RULES:
1. Always respond with valid JSON containing an "action" field
2. You can include a "message" field to say something while performing the action
3. Be helpful and friendly
4. If you don't understand, ask for clarification with {"action": "idle", "message": "your question"}
5. Consider the context - don't mine diamonds if asked about the weather

Example responses:
User: "Get me some wood"
Response: {"action": "gather", "item": "oak_log", "count": 32, "message": "On it! I'll get you some oak logs."}

User: "How are you?"
Response: {"action": "idle", "message": "I'm doing great! Ready to help you with anything."}

User: "Kill that zombie!"
Response: {"action": "attack", "target": "zombie", "message": "Fighting the zombie!"}
""".formatted(companionName);
    }

    public CompletableFuture<CompanionAction> chat(String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Add user message to history
                conversationHistory.add(new ChatMessage("user", userMessage));

                // Build request
                String response = sendChatRequest();

                // Add assistant response to history
                conversationHistory.add(new ChatMessage("assistant", response));

                // Parse response into action
                return parseResponse(response);
            } catch (Exception e) {
                Player2NPC.LOGGER.error("Ollama chat error: ", e);
                return new CompanionAction("idle", "Sorry, I'm having trouble thinking right now.");
            }
        });
    }

    private String sendChatRequest() throws Exception {
        String host = Config.OLLAMA_HOST.get();
        int port = Config.OLLAMA_PORT.get();
        String model = Config.OLLAMA_MODEL.get();

        String url = String.format("http://%s:%d/api/chat", host, port);

        // Build messages array
        JsonArray messages = new JsonArray();

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        // Conversation history (keep last 20 messages for context)
        int startIdx = Math.max(0, conversationHistory.size() - 20);
        for (int i = startIdx; i < conversationHistory.size(); i++) {
            ChatMessage msg = conversationHistory.get(i);
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role());
            msgObj.addProperty("content", msg.content());
            messages.add(msgObj);
        }

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.add("messages", messages);
        requestBody.addProperty("stream", false);

        // Options for faster response
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.7);
        options.addProperty("num_predict", 256);
        requestBody.add("options", options);

        int timeout = Config.OLLAMA_TIMEOUT.get();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama request failed: " + response.statusCode() + " - " + response.body());
        }

        String responseBody = response.body();
        JsonObject json = GSON.fromJson(responseBody, JsonObject.class);

        if (json.has("message") && json.getAsJsonObject("message").has("content")) {
            return json.getAsJsonObject("message").get("content").getAsString();
        }

        return "{\"action\": \"idle\", \"message\": \"I didn't get a proper response.\"}";
    }

    private CompanionAction parseResponse(String response) {
        try {
            // Try to extract JSON from response
            String jsonStr = response.trim();

            // Handle case where LLM wraps JSON in markdown
            if (jsonStr.contains("```json")) {
                int start = jsonStr.indexOf("```json") + 7;
                int end = jsonStr.indexOf("```", start);
                if (end > start) {
                    jsonStr = jsonStr.substring(start, end).trim();
                }
            } else if (jsonStr.contains("```")) {
                int start = jsonStr.indexOf("```") + 3;
                int end = jsonStr.indexOf("```", start);
                if (end > start) {
                    jsonStr = jsonStr.substring(start, end).trim();
                }
            }

            // Find JSON object in response
            int jsonStart = jsonStr.indexOf('{');
            int jsonEnd = jsonStr.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
            }

            JsonObject json = GSON.fromJson(jsonStr, JsonObject.class);
            return CompanionAction.fromJson(json);
        } catch (Exception e) {
            Player2NPC.LOGGER.warn("Failed to parse LLM response as JSON: {}", response);
            // Return idle action with the raw response as message
            return new CompanionAction("idle", response);
        }
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public record ChatMessage(String role, String content) {}
}
