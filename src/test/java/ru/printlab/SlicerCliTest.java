package ru.printlab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import ru.printlab.config.AppProperties;
import ru.printlab.order.OrderStatus;
import ru.printlab.order.PrintOrder;
import ru.printlab.slicing.GcodeEstimateParser;
import ru.printlab.slicing.CliSlicingEngine;
import ru.printlab.slicing.SliceJob;
import ru.printlab.slicing.SliceResult;
import ru.printlab.slicing.SlicerProperties;
import ru.printlab.slicing.SlicerType;
import ru.printlab.storage.ModelFile;
import ru.printlab.storage.ModelStorage;
import ru.printlab.storage.StlValidator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in tests against a real installed binary and explicitly selected test presets. */
@EnabledIfEnvironmentVariable(named = "SLICER_TEST_EXECUTABLE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SLICER_TEST_PROFILES", matches = ".+")
class SlicerCliTest {
    @TempDir Path directory;

    @Test
    void actualEngineAccountsForQuantityAndProducesPresetProvenance() {
        SliceResult one = slice(1, "STANDARD", 15);
        SliceResult two = slice(2, "STANDARD", 15);
        assertThat(one.estimate().grams()).isPositive();
        assertThat(one.estimate().printMinutes()).isPositive();
        assertThat(two.estimate().grams()).isGreaterThan(one.estimate().grams().multiply(new java.math.BigDecimal("1.5")));
        assertThat(two.estimate().printMinutes()).isGreaterThan(one.estimate().printMinutes());
        assertThat(two.provenance()).contains("quantity=2", "machine=", "process=", "filament=");
        System.out.println("REAL SLICER STANDARD: quantity=1 " + one.estimate() + "; quantity=2 " + two.estimate());
    }

    @Test
    void actualEngineHonorsFineProfileAndInfill() {
        SliceResult standard = slice(1, "STANDARD", 15);
        SliceResult fine = slice(1, "FINE", 60);
        assertThat(fine.estimate().grams()).isGreaterThan(standard.estimate().grams());
        assertThat(fine.estimate().printMinutes()).isGreaterThan(standard.estimate().printMinutes());
        assertThat(fine.provenance()).contains("profile=FINE", "infill=60");
        System.out.println("REAL SLICER FINE: " + fine.estimate());
    }

    @Test
    void smallA1MiniBedSplitsLargeBatchAndCountsEveryObject() {
        SliceResult result = slice(4, "STANDARD", 15, box(100, 100, 10));
        assertThat(result.estimate().plateCount()).isBetween(2, 4);
        assertThat(result.provenance()).contains("quantity=4", "plates=" + result.estimate().plateCount());
        System.out.println("REAL SLICER MULTIPLATE: " + result.estimate());
    }

    private SliceResult slice(int quantity, String profile, int infill) {
        return slice(quantity, profile, infill, box(20, 20, 20));
    }

    private SliceResult slice(int quantity, String profile, int infill, byte[] bytes) {
        AppProperties app = new AppProperties(true, false, "", Set.of(), directory.resolve("models"), false,
                null, null, Map.of(), "", "", "", "");
        SlicerProperties config = new SlicerProperties(true, false, System.getenv("SLICER_TEST_EXECUTABLE"),
                Path.of(System.getenv("SLICER_TEST_PROFILES")), directory.resolve("jobs"),
                "native-test", 60, Map.of("pla-black", "pla.json"), SlicerType.valueOf(System.getenv().getOrDefault("SLICER_TEST_ENGINE", "BAMBU_STUDIO")));
        ModelStorage storage = new ModelStorage(app, new StlValidator());
        UUID customer = UUID.randomUUID();
        ModelFile model = storage.save(customer, "native-test-file", "box.stl", bytes);
        PrintOrder order = new PrintOrder(UUID.randomUUID(), 1, customer, OrderStatus.ESTIMATING, model.id(),
                "pla-black", profile, infill, quantity, "", null, Instant.now(), null);
        CliSlicingEngine engine = new CliSlicingEngine(config, app, new GcodeEstimateParser());
        return engine.slice(new SliceJob(UUID.randomUUID(), order.id(), 0), order, model);
    }

    private byte[] box(int width, int depth, int height) {
        int[][] vertices = {{0, 0, 0}, {width, 0, 0}, {width, depth, 0}, {0, depth, 0},
                {0, 0, height}, {width, 0, height}, {width, depth, height}, {0, depth, height}};
        int[][] triangles = {{0, 2, 1}, {0, 3, 2}, {4, 5, 6}, {4, 6, 7}, {0, 1, 5}, {0, 5, 4},
                {1, 2, 6}, {1, 6, 5}, {2, 3, 7}, {2, 7, 6}, {3, 0, 4}, {3, 4, 7}};
        StringBuilder stl = new StringBuilder("solid cube20\n");
        for (int[] triangle : triangles) {
            stl.append("facet normal 0 0 0\nouter loop\n");
            for (int index : triangle) {
                int[] vertex = vertices[index];
                stl.append("vertex ").append(vertex[0]).append(' ').append(vertex[1]).append(' ').append(vertex[2]).append('\n');
            }
            stl.append("endloop\nendfacet\n");
        }
        stl.append("endsolid cube20\n");
        return stl.toString().getBytes(StandardCharsets.UTF_8);
    }
}
