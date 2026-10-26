package ru.printlab.slicing;

import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.pricing.Estimate;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;

@Component
public class GcodeEstimateParser {
    private static final Pattern TIME = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([dhms])");
    private static final Pattern OBJECTS = Pattern.compile("; object ids of layer [0-9]+ start: (.+)");

    public Estimate parse(Path gcode) throws IOException {
        return plate(gcode).estimate();
    }

    public PlateEstimate plate(Path gcode) throws IOException {
        if (Files.size(gcode) > 512L * 1024 * 1024) {
            throw new BusinessException("Результат слайсинга превышает лимит.");
        }
        BigDecimal totalGrams = null;
        BigDecimal extruderGrams = null;
        BigDecimal minutes = null;
        Set<String> objectIds = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(gcode)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("; total filament used [g] = ")) {
                    totalGrams = grams(line.substring(line.indexOf('=') + 1));
                } else if (line.startsWith("; total filament weight [g] : ")) {
                    totalGrams = grams(line.substring(line.indexOf(':') + 1));
                } else if (line.startsWith("; filament used [g] = ")) {
                    extruderGrams = grams(line.substring(line.indexOf('=') + 1));
                } else if (line.startsWith("; estimated printing time (normal mode) = ")) {
                    minutes = minutes(line.substring(line.indexOf('=') + 1));
                } else if (line.startsWith("; model printing time:") && line.contains("total estimated time:")) {
                    minutes = minutes(line.substring(line.indexOf("total estimated time:") + 21));
                }
                if (line.startsWith("; filament: ") && line.substring(12).trim().split(",").length > 1) {
                    throw new BusinessException("MVP поддерживает один материал и один цвет на заказ.");
                }
                if (line.startsWith("; model label id: ")) {
                    for (String objectId : line.substring(18).split(",")) {
                        if (!objectId.isBlank()) {
                            objectIds.add(objectId.trim());
                        }
                    }
                }
                if (line.startsWith("; OBJECT_ID: ")) {
                    String objectId = line.substring(13).trim();
                    if (objectId.matches("[0-9]+")) {
                        objectIds.add(objectId);
                    }
                }
                Matcher objects = OBJECTS.matcher(line);
                if (objects.matches()) {
                    for (String objectId : objects.group(1).split(",")) {
                        objectIds.add(objectId.trim());
                    }
                }
            }
        }
        if (totalGrams == null) {
            totalGrams = extruderGrams;
        }
        if (totalGrams == null || minutes == null || totalGrams.signum() <= 0 || minutes.signum() <= 0) {
            throw new BusinessException("Слайсер не вернул достоверную массу и время печати.");
        }
        Estimate estimate = new Estimate(totalGrams, BigDecimal.ZERO, minutes, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        estimate.validate();
        return new PlateEstimate(estimate, objectIds.size());
    }

    private BigDecimal grams(String text) {
        BigDecimal total = BigDecimal.ZERO;
        int usedFilaments = 0;
        for (String item : text.trim().split(",")) {
            if (!item.trim().matches("[0-9]+(?:\\.[0-9]+)?")) {
                throw new BusinessException("Неизвестный формат массы в результате слайсера.");
            }
            BigDecimal grams = new BigDecimal(item.trim());
            if (grams.signum() > 0) {
                usedFilaments++;
            }
            total = total.add(grams);
        }
        if (usedFilaments > 1) {
            throw new BusinessException("MVP поддерживает один материал и один цвет на заказ.");
        }
        return total;
    }

    public BigDecimal minutes(String text) {
        Matcher matcher = TIME.matcher(text.trim());
        BigDecimal seconds = BigDecimal.ZERO;
        int end = 0;
        while (matcher.find()) {
            if (!text.trim().substring(end, matcher.start()).isBlank()) {
                throw new BusinessException("Неизвестный формат времени слайсера.");
            }
            BigDecimal multiplier = switch (matcher.group(2)) {
                case "d" -> new BigDecimal("86400");
                case "h" -> new BigDecimal("3600");
                case "m" -> new BigDecimal("60");
                default -> BigDecimal.ONE;
            };
            seconds = seconds.add(new BigDecimal(matcher.group(1)).multiply(multiplier));
            end = matcher.end();
        }
        if (end == 0 || !text.trim().substring(end).isBlank()) {
            throw new BusinessException("Неизвестный формат времени слайсера.");
        }
        return seconds.divide(new BigDecimal("60"), 4, RoundingMode.CEILING);
    }
}
