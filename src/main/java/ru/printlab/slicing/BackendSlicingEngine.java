package ru.printlab.slicing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.config.AppProperties;
import ru.printlab.order.PrintOrder;
import ru.printlab.pricing.Estimate;
import ru.printlab.storage.ModelFile;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "printlab.slicer.transport", havingValue = "HTTP")
public class BackendSlicingEngine implements SlicingEngine {
    private static final int MAX_MODEL_BYTES = 15 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final BackendSlicerProperties backend;
    private final SlicerProperties slicer;
    private final AppProperties app;
    private final Json json;

    public BackendSlicingEngine(BackendSlicerProperties backend, SlicerProperties slicer,
                               AppProperties app, Json json) {
        this.backend = backend;
        this.slicer = slicer;
        this.app = app;
        this.json = json;
    }

    @Override
    public SliceResult slice(SliceJob job, PrintOrder order, ModelFile model) {
        String material = backend.materials().get(order.materialId());
        if (material == null || !material.matches("[a-z0-9-]{1,100}")) {
            throw new BusinessException("Не настроено соответствие материала каталогу backend слайсера.");
        }
        String layer = "0.20";
        if (order.profile().equals("FINE")) {
            layer = "0.12";
        }
        try {
            var modelPath = app.storageDir().toAbsolutePath().resolve(model.storageName());
            if (Files.size(modelPath) > MAX_MODEL_BYTES) {
                throw new BusinessException("Автоматический backend принимает STL до 15 МиБ. Нужен ручной расчёт оператора.");
            }
            String boundary = "printlab-" + UUID.randomUUID();
            byte[] body = multipart(boundary, Files.readAllBytes(modelPath), material, layer, order.infill());
            HttpRequest request = HttpRequest.newBuilder(backend.url())
                    .timeout(Duration.ofSeconds(slicer.timeoutSeconds()))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() == 429 || response.statusCode() == 503 || response.statusCode() == 502) {
                throw new RetryableSlicingException("Backend слайсера занят или временно недоступен.");
            }
            if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                throw new BusinessException("Backend слайсера не рассчитал модель (HTTP " + response.statusCode() + "). Нужна проверка оператора.");
            }
            JsonNode result = json.read(new String(bytes, StandardCharsets.UTF_8), JsonNode.class);
            return result(result, job, order, model, layer, material);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Автоматический расчёт прерван.");
        } catch (IOException exception) {
            throw new RetryableSlicingException("Не удалось связаться с backend слайсера или прочитать STL.");
        }
    }

    private SliceResult result(JsonNode result, SliceJob job, PrintOrder order, ModelFile model,
                               String layer, String material) {
        if (!result.path("printerName").asText().equals(app.printer().model())
                || result.path("nozzleDiameterMm").asDouble() != app.printer().nozzleDiameterMm().doubleValue()
                || result.path("engineVersion").asText("").isBlank()) {
            throw new BusinessException("Ответ слайсера не соответствует принтеру A1 mini и настроенному соплу.");
        }
        for (JsonNode candidate : result.path("candidates")) {
            if (candidate.path("infillPercent").asInt() != order.infill()
                    || candidate.path("perimeters").asInt() != 2
                    || candidate.path("layerHeightMm").asDouble() != Double.parseDouble(layer)
                    || !candidate.path("meetsConstraints").asBoolean()) {
                continue;
            }
            double grams = candidate.path("estimatedMaterialG").asDouble();
            double minutes = candidate.path("estimatedPrintTimeMin").asDouble();
            if (!Double.isFinite(grams) || !Double.isFinite(minutes) || grams <= 0 || minutes <= 0) {
                throw new BusinessException("Backend вернул некорректные расход или время печати.");
            }
            BigDecimal quantity = BigDecimal.valueOf(order.quantity());
            // The existing API slices one object. Each item is a separate plate, including preparation.
            Estimate estimate = new Estimate(BigDecimal.valueOf(grams).multiply(quantity), BigDecimal.ZERO,
                    BigDecimal.valueOf(minutes).multiply(quantity), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, order.quantity());
            estimate.validate();
            String provenance = "BAMBU_STUDIO_HTTP=" + result.path("engineVersion").asText()
                    + "; modelSha256=" + model.sha256() + "; material=" + material + "; profile=" + order.profile()
                    + "; infill=" + order.infill() + "; perimeters=2; candidate=" + candidate.path("id").asText()
                    + "; quantity=" + order.quantity() + "; plates=" + order.quantity() + "; layout=one-item-per-plate"
                    + "; job=" + job.id() + "; warnings=" + json.write(result.path("warnings"));
            return new SliceResult(estimate, provenance);
        }
        throw new BusinessException("Слайсер не рассчитал выбранные слой и заполнение. Нужна проверка оператора.");
    }

    private byte[] multipart(String boundary, byte[] model, String material, String layer, int infill) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("materialProfileId", material);
        fields.put("priority", "BALANCED");
        fields.put("layerHeightMm", layer);
        fields.put("minimumInfillPercent", Integer.toString(infill));
        fields.put("minimumPerimeters", "2");
        fields.put("supportMode", "AUTO");
        fields.put("maxPrintTimeMin", "0");
        fields.put("maxMaterialConsumptionG", "0");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"model.stl\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(model);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }
}
