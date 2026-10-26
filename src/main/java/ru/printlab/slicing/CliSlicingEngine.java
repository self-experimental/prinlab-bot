package ru.printlab.slicing;

import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.config.AppProperties;
import ru.printlab.order.PrintOrder;
import ru.printlab.pricing.Estimate;
import ru.printlab.storage.ModelFile;

import java.io.IOException;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class CliSlicingEngine implements SlicingEngine {
    private final SlicerProperties config;
    private final AppProperties app;
    private final GcodeEstimateParser parser;

    public CliSlicingEngine(SlicerProperties config, AppProperties app, GcodeEstimateParser parser) {
        this.config = config;
        this.app = app;
        this.parser = parser;
        if (config.enabled() && !app.demoMode() && !config.profileConfirmed()) {
            throw new IllegalStateException("Автослайсинг требует проверенных профилей и SLICER_PROFILE_CONFIRMED=true");
        }
    }

    @Override
    public SliceResult slice(SliceJob job, PrintOrder order, ModelFile model) {
        Path work = config.workDir().toAbsolutePath().resolve(job.id().toString() + "-" + job.attempts());
        Process process = null;
        try {
            Files.createDirectories(work);
            Path profiles = config.profilesDir().toAbsolutePath().normalize();
            Path machine = profile(profiles, "machine.json");
            String processName = "standard.json";
            if (order.profile().equals("FINE")) {
                processName = "fine.json";
            }
            Path processProfile = profile(profiles, processName);
            String filamentName = config.materialProfiles().get(order.materialId());
            if (filamentName == null) {
                throw new BusinessException("Не настроен профиль материала для автоматического слайсинга.");
            }
            Path filament = profile(profiles, filamentName);
            validateMachine(machine);
            // Keep the exact preset bytes used by this job for later reproduction.
            Files.copy(machine, work.resolve("machine.json"), StandardCopyOption.REPLACE_EXISTING);
            prepareProcess(processProfile, work.resolve("process.json"), order);
            Files.copy(filament, work.resolve("filament.json"), StandardCopyOption.REPLACE_EXISTING);
            Path modelPath = app.storageDir().toAbsolutePath().resolve(model.storageName());
            List<String> command = command(work, modelPath, order);
            ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            // Slicer and preset hooks do not need bot or database credentials.
            builder.environment().remove("TELEGRAM_BOT_TOKEN");
            builder.environment().remove("DB_PASSWORD");
            process = builder.start();
            if (!process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS)) {
                stop(process);
                throw new BusinessException("Превышено время автоматического слайсинга. Нужна проверка модели.");
            }
            if (process.exitValue() != 0) {
                throw new BusinessException("Слайсер отклонил модель или профиль. Код: " + process.exitValue() + ".");
            }
            List<Path> gcodes;
            try (var files = Files.list(work)) {
                gcodes = files.filter(path -> path.getFileName().toString().endsWith(".gcode")).toList();
            }
            if (gcodes.isEmpty()) {
                throw new BusinessException("Слайсер не создал G-code. Проверьте CLI и профили.");
            }
            BigDecimal grams = BigDecimal.ZERO;
            BigDecimal minutes = BigDecimal.ZERO;
            int objectCount = 0;
            for (Path gcode : gcodes) {
                PlateEstimate plate = parser.plate(gcode);
                grams = grams.add(plate.estimate().grams());
                minutes = minutes.add(plate.estimate().printMinutes());
                objectCount += plate.objectCount();
            }
            if (objectCount != order.quantity()) {
                throw new BusinessException("Слайсер обработал " + objectCount + " из " + order.quantity()
                        + " изделий. Проверьте размещение на столе A1 mini; цена не сформирована.");
            }
            Estimate estimate = new Estimate(grams, BigDecimal.ZERO, minutes, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, gcodes.size());
            String provenance = config.engine() + "=" + config.version() + "; modelSha256=" + model.sha256()
                    + "; quantity=" + order.quantity() + "; plates=" + gcodes.size() + "; infill=" + order.infill() + "; profile=" + order.profile()
                    + "; machine=" + hash(work.resolve("machine.json")) + "; process=" + hash(work.resolve("process.json"))
                    + "; filament=" + hash(work.resolve("filament.json")) + "; job=" + job.id();
            return new SliceResult(estimate, provenance);
        } catch (InterruptedException exception) {
            if (process != null) {
                stop(process);
            }
            Thread.currentThread().interrupt();
            throw new BusinessException("Слайсинг прерван. Повторите расчёт.");
        } catch (IOException exception) {
            throw new BusinessException("Не удалось запустить слайсер или прочитать его файлы. Проверьте пути и профили.");
        }
    }

    public List<String> command(Path work, Path model, PrintOrder order) {
        List<String> args = new ArrayList<>();
        args.add(config.executable());
        args.addAll(List.of("--datadir", work.resolve("config").toString(),
                "--load-settings", work.resolve("machine.json") + ";" + work.resolve("process.json"),
                "--load-filaments", work.resolve("filament.json").toString(),
                "--clone-objects", String.valueOf(order.quantity()), "--load-filament-ids", "1",
                "--arrange", "1", "--ensure-on-bed", "--slice", "0",
                "--mstpp", String.valueOf(config.timeoutSeconds()), "--mtcpp", "400000",
                "--outputdir", work.toString()));
        args.addAll(List.of("--orient", "1"));
        args.add(model.toString());
        return args;
    }

    private void prepareProcess(Path source, Path target, PrintOrder order) throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode parsed = mapper.readTree(Files.readString(source));
        if (!(parsed instanceof ObjectNode process)) {
            throw new BusinessException("Неверный формат профиля процесса.");
        }
        String layerHeight = "0.20";
        if (order.profile().equals("FINE")) {
            layerHeight = "0.12";
        }
        process.put("layer_height", layerHeight);
        process.put("sparse_infill_density", order.infill() + "%");
        process.put("enable_support", "1");
        Files.writeString(target, mapper.writeValueAsString(process));
    }

    private void validateMachine(Path file) throws IOException {
        JsonNode machine = JsonMapper.builder().build().readTree(Files.readString(file));
        if (!app.printer().model().equals(machine.path("printer_model").asText())
                || machine.path("printable_height").asDouble() != app.printer().heightMm().doubleValue()
                || machine.path("nozzle_diameter").size() != 1
                || machine.path("nozzle_diameter").get(0).asDouble() != app.printer().nozzleDiameterMm().doubleValue()) {
            throw new BusinessException("Профиль должен соответствовать A1 mini и настроенному соплу.");
        }
        Set<String> expected = Set.of("0x0", "180x0", "180x180", "0x180");
        JsonNode area = machine.path("printable_area");
        if (area.size() != 4) {
            throw new BusinessException("Профиль должен ограничивать стол A1 mini областью 180×180 мм.");
        }
        java.util.HashSet<String> points = new java.util.HashSet<>();
        for (JsonNode point : area) {
            points.add(point.asText());
        }
        if (!points.equals(expected)) {
            throw new BusinessException("Профиль должен ограничивать стол A1 mini областью 180×180 мм.");
        }
    }

    private Path profile(Path root, String name) {
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("Не найден утверждённый профиль слайсера: " + name);
        }
        return file;
    }

    private String hash(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void stop(Process process) {
        for (ProcessHandle child : process.descendants().toList()) {
            child.destroyForcibly();
        }
        process.destroyForcibly();
    }
}
