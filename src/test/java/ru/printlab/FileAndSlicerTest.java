package ru.printlab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.printlab.common.BusinessException;
import ru.printlab.order.OrderStatus;
import ru.printlab.order.PrintOrder;
import ru.printlab.slicing.GcodeEstimateParser;
import ru.printlab.slicing.CliSlicingEngine;
import ru.printlab.slicing.SlicerProperties;
import ru.printlab.slicing.BackendSlicerProperties;
import ru.printlab.slicing.BackendSlicingEngine;
import ru.printlab.slicing.RetryableSlicingException;
import ru.printlab.slicing.SliceJob;
import ru.printlab.common.Json;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import ru.printlab.storage.StlValidator;
import ru.printlab.storage.ModelStorage;
import ru.printlab.config.AppProperties;
import java.util.UUID;
import java.util.Set;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class FileAndSlicerTest {
    @TempDir Path directory;
    private final StlValidator validator = new StlValidator();
    private final GcodeEstimateParser parser = new GcodeEstimateParser();

    @Test
    void acceptsBinaryStlEvenWhenHeaderStartsWithSolid() {
        byte[] bytes = Fixtures.stl();
        System.arraycopy("solid header".getBytes(), 0, bytes, 0, 12);
        assertThatCode(() -> validator.validate("part.STL", bytes)).doesNotThrowAnyException();
    }

    @Test
    void rejectsForgedTriangleCountAndNonFiniteCoordinates() {
        byte[] bytes = Fixtures.stl();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(80, Integer.MAX_VALUE);
        assertThatThrownBy(() -> validator.validate("part.stl", bytes)).isInstanceOf(BusinessException.class);
        byte[] infinite = Fixtures.stl();
        ByteBuffer.wrap(infinite).order(ByteOrder.LITTLE_ENDIAN).putFloat(96, Float.NaN);
        assertThatThrownBy(() -> validator.validate("part.stl", infinite)).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsStructuredAsciiAndRejectsFakeWrapper() {
        String ascii = """
                solid part
                facet normal 0 0 1
                outer loop
                vertex 0 0 0
                vertex 1 0 0
                vertex 0 1 0
                endloop
                endfacet
                endsolid part
                """;
        assertThatCode(() -> validator.validate("part.stl", ascii.getBytes())).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate("part.stl", "solid part\nendsolid part".getBytes()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate("part.exe", Fixtures.stl())).isInstanceOf(BusinessException.class);
    }

    @Test
    void parsesNormalGcodeWithoutDoubleCountingTotalAndPerExtruderWeight() throws Exception {
        Path gcode = directory.resolve("plate_1.gcode");
        Files.writeString(gcode, """
                ; filament used [g] = 12.75, 0.00
                ; total filament used [g] = 12.75
                ; estimated printing time (normal mode) = 1h 30m 15s
                ; estimated printing time (silent mode) = 2h 0m 0s
                """);
        var estimate = parser.parse(gcode);
        assertThat(estimate.grams()).isEqualByComparingTo("12.75");
        assertThat(estimate.printMinutes()).isEqualByComparingTo("90.25");
    }

    @Test
    void parsesBambuTotalTimeIncludingPreparationAndRejectsMissingMass() throws Exception {
        Path gcode = directory.resolve("plate_1.gcode");
        Files.writeString(gcode, "; filament used [g] = 12.75\n; model printing time: 1h 20m; total estimated time: 1h 30m\n");
        assertThat(parser.parse(gcode).printMinutes()).isEqualByComparingTo("90.00");
        Files.writeString(gcode, "; estimated printing time (normal mode) = 1h\n");
        assertThatThrownBy(() -> parser.parse(gcode)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.minutes("2 hours maybe")).isInstanceOf(BusinessException.class);
    }

    @Test
    void passesQuantityAndAbsolutePathsAsSeparateProcessArguments() throws Exception {
        SlicerProperties config = new SlicerProperties(false, false, "/opt/orca slicer", directory,
                directory, "test", 180, Map.of("pla-black", "pla.json"));
        CliSlicingEngine engine = new CliSlicingEngine(config, null, parser);
        PrintOrder order = new PrintOrder(UUID.randomUUID(), 1, UUID.randomUUID(), OrderStatus.ESTIMATING,
                UUID.randomUUID(), "pla-black", "FINE", 30, 5, "", null, Instant.now(), null);
        List<String> args = engine.command(directory, directory.resolve("model.stl"), order);
        assertThat(args).containsSubsequence("--clone-objects", "5");
        assertThat(args).containsSubsequence("--slice", "0");
        assertThat(args).doesNotContain("--scale", "--convert-unit", "--export-3mf", "sh", "-c");

        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>("""
                {"printerName":"Bambu Lab A1 mini","nozzleDiameterMm":0.4,"engineVersion":"02.08.02.61",
                 "selectedCandidateId":"other","candidates":[
                  {"id":"other","infillPercent":40,"perimeters":3,"layerHeightMm":0.08,
                   "meetsConstraints":true,"estimatedMaterialG":1,"estimatedPrintTimeMin":1},
                  {"id":"requested","infillPercent":30,"perimeters":2,"layerHeightMm":0.12,
                   "meetsConstraints":true,"estimatedMaterialG":3.5,"estimatedPrintTimeMin":12.5}],"warnings":[]}
                """);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze-stl", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            AppProperties app = new AppProperties(true, false, "", Set.of(), directory, false,
                    null, null, Map.of(), "", "", "", "");
            var model = new ModelStorage(app, validator).save(order.customerId(), "test", "part.stl", Fixtures.stl());
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/analyze-stl");
            var backend = new BackendSlicerProperties(url, Map.of("pla-black", "prusament-pla"));
            var remote = new BackendSlicingEngine(backend, config, app, new Json(JsonMapper.builder().build()));
            SliceJob job = new SliceJob(UUID.randomUUID(), order.id(), 0);
            var result = remote.slice(job, order, model);
            assertThat(result.estimate().grams()).isEqualByComparingTo("17.5");
            assertThat(result.estimate().printMinutes()).isEqualByComparingTo("62.5");
            assertThat(result.estimate().plateCount()).isEqualTo(5);
            assertThat(result.provenance()).contains("candidate=requested", "quantity=5", "one-item-per-plate", model.sha256());
            assertThat(requestBody.get()).contains("name=\"minimumInfillPercent\"\r\n\r\n30",
                    "name=\"layerHeightMm\"\r\n\r\n0.12", "prusament-pla", "name=\"supportMode\"\r\n\r\nAUTO");
            status.set(429);
            assertThatThrownBy(() -> remote.slice(job, order, model)).isInstanceOf(RetryableSlicingException.class);
            status.set(200);
            responseBody.set(responseBody.get().replace("\"infillPercent\":30", "\"infillPercent\":60"));
            assertThatThrownBy(() -> remote.slice(job, order, model)).isInstanceOf(ru.printlab.common.BusinessException.class)
                    .hasMessageContaining("выбранные слой и заполнение");
            responseBody.set("{\"printerName\":\"Other printer\"}");
            assertThatThrownBy(() -> remote.slice(job, order, model)).isInstanceOf(ru.printlab.common.BusinessException.class)
                    .hasMessageContaining("A1 mini");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsModelsAboveA1MiniDimensionsBeforeSavingAndAcceptsExactBoundary() {
        AppProperties app = new AppProperties(true, false, "", Set.of(), directory, false,
                null, null, Map.of(), "", "", "", "");
        ModelStorage storage = new ModelStorage(app, validator);
        byte[] fitting = Fixtures.stl();
        ByteBuffer.wrap(fitting).order(ByteOrder.LITTLE_ENDIAN).putFloat(108, 180);
        assertThatCode(() -> storage.save(UUID.randomUUID(), "test", "fits.stl", fitting)).doesNotThrowAnyException();
        byte[] tooWide = Fixtures.stl();
        ByteBuffer.wrap(tooWide).order(ByteOrder.LITTLE_ENDIAN).putFloat(108, 181);
        assertThatThrownBy(() -> storage.save(UUID.randomUUID(), "test", "large.stl", tooWide))
                .isInstanceOf(BusinessException.class).hasMessageContaining("180×180×180");
    }

    @Test
    void newBambuHeaderSupportsOneObjectAndRefusesMultipleFilaments() throws Exception {
        Path gcode = directory.resolve("bambu.gcode");
        Files.writeString(gcode, """
                ; model printing time: 16m 3s; total estimated time: 22m 8s
                ; total filament weight [g] : 3.64
                ; filament: 1
                ; OBJECT_ID: 15
                ; OBJECT_ID: 15
                """);
        assertThat(parser.plate(gcode).objectCount()).isEqualTo(1);
        assertThat(parser.parse(gcode).grams()).isEqualByComparingTo("3.64");
        Files.writeString(gcode, """
                ; model printing time: 16m 3s; total estimated time: 22m 8s
                ; total filament weight [g] : 3.64
                ; filament: 1,2
                """);
        assertThatThrownBy(() -> parser.parse(gcode)).isInstanceOf(BusinessException.class).hasMessageContaining("один цвет");
    }
}
