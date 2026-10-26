package ru.printlab.telegram;

import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.config.AppProperties;
import ru.printlab.storage.StlValidator;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TelegramHttpGateway implements TelegramGateway {
    private static final Set<String> OUTGOING = Set.of("sendMessage", "sendDocument", "sendPhoto", "answerCallbackQuery");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AppProperties properties;
    private final Json json;

    public TelegramHttpGateway(AppProperties properties, Json json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public JsonNode updates(long offset) {
        return request("getUpdates", json.write(Map.of("offset", offset, "timeout", 25, "limit", 20,
                "allowed_updates", List.of("message", "callback_query"))));
    }

    @Override
    public void deliver(String method, String payload) {
        if (!OUTGOING.contains(method)) {
            throw new IllegalArgumentException("Unsupported outgoing method");
        }
        request(method, payload);
    }

    private JsonNode request(String method, String payload) {
        try {
            URI uri = URI.create("https://api.telegram.org/bot" + properties.botToken() + "/" + method);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            }
            if (bytes.length > 2 * 1024 * 1024) {
                throw new TelegramFailure(false, 30);
            }
            JsonNode body = json.read(new String(bytes, StandardCharsets.UTF_8), JsonNode.class);
            if (response.statusCode() != 200 || !body.path("ok").asBoolean()) {
                int code = body.path("error_code").asInt(response.statusCode());
                boolean permanent = code == 400 || code == 401 || code == 403 || code == 404;
                throw new TelegramFailure(permanent, body.path("parameters").path("retry_after").asInt(30));
            }
            return body.path("result");
        } catch (TelegramFailure exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelegramFailure(false, 30);
        } catch (Exception exception) {
            // HttpClient exceptions may contain the token in their URI. Never propagate them.
            throw new TelegramFailure(false, 30);
        }
    }

    @Override
    public byte[] download(String fileId) {
        JsonNode file = request("getFile", json.write(Map.of("file_id", fileId)));
        if (file.path("file_size").asLong() > StlValidator.MAX_BYTES) {
            throw new BusinessException("Файл превышает лимит 20 МиБ.");
        }
        String path = file.path("file_path").asText();
        if (!path.matches("[a-zA-Z0-9_./-]+") || path.contains("..") || path.startsWith("/")) {
            throw new TelegramFailure(true, 0);
        }
        try {
            URI uri = URI.create("https://api.telegram.org/file/bot" + properties.botToken() + "/" + path);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) {
                    throw new TelegramFailure(false, 30);
                }
                byte[] bytes = input.readNBytes(StlValidator.MAX_BYTES + 1);
                if (bytes.length > StlValidator.MAX_BYTES) {
                    throw new BusinessException("Файл превышает лимит 20 МиБ.");
                }
                return bytes;
            }
        } catch (BusinessException | TelegramFailure exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelegramFailure(false, 30);
        } catch (Exception exception) {
            throw new TelegramFailure(false, 30);
        }
    }
}
