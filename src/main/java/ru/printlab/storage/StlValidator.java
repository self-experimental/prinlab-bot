package ru.printlab.storage;

import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class StlValidator {
    public static final int MAX_BYTES = 20 * 1024 * 1024;

    public void validate(String name, byte[] bytes) {
        inspect(name, bytes);
    }

    public ModelDimensions inspect(String name, byte[] bytes) {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".stl")) {
            throw new BusinessException("Для печати нужен STL-файл. Для моделирования можно прислать фото или эскиз.");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BusinessException("Пришли непустой STL-файл размером до 20 МБ.");
        }
        if (bytes.length >= 84) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            long triangles = Integer.toUnsignedLong(buffer.getInt(80));
            if (triangles > 0 && 84L + triangles * 50L == bytes.length) {
                BoundsAccumulator bounds = new BoundsAccumulator();
                for (int position = 84; position < bytes.length; position += 50) {
                    for (int offset = 0; offset < 48; offset += 4) {
                        if (!Float.isFinite(buffer.getFloat(position + offset))) {
                            throw new BusinessException("STL содержит некорректные координаты.");
                        }
                    }
                    for (int vertex = 12; vertex < 48; vertex += 12) {
                        bounds.add(buffer.getFloat(position + vertex), buffer.getFloat(position + vertex + 4),
                                buffer.getFloat(position + vertex + 8));
                    }
                }
                return bounds.dimensions();
            }
        }
        return validateAscii(bytes);
    }

    private ModelDimensions validateAscii(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                throw invalid();
            }
        }
        try (BufferedReader reader = new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            String line = next(reader);
            if (line == null || !line.matches("solid(?:\\s.*)?")) {
                throw invalid();
            }
            int facets = 0;
            BoundsAccumulator bounds = new BoundsAccumulator();
            while ((line = next(reader)) != null && !line.matches("endsolid(?:\\s.*)?")) {
                coordinates(line, "facet normal");
                expect(reader, "outer loop");
                for (int vertex = 0; vertex < 3; vertex++) {
                    double[] values = coordinates(next(reader), "vertex");
                    bounds.add(values[0], values[1], values[2]);
                }
                expect(reader, "endloop");
                expect(reader, "endfacet");
                facets++;
            }
            if (facets == 0 || line == null || next(reader) != null) {
                throw invalid();
            }
            return bounds.dimensions();
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private String next(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return null;
    }

    private void expect(BufferedReader reader, String expected) throws IOException {
        if (!expected.equals(next(reader))) {
            throw invalid();
        }
    }

    private double[] coordinates(String line, String prefix) {
        if (line == null || !line.startsWith(prefix + " ")) {
            throw invalid();
        }
        String[] values = line.substring(prefix.length()).trim().split("\\s+");
        if (values.length != 3) {
            throw invalid();
        }
        double[] numbers = new double[3];
        for (int index = 0; index < values.length; index++) {
            try {
                numbers[index] = Double.parseDouble(values[index]);
                if (!Double.isFinite(numbers[index])) {
                    throw invalid();
                }
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }
        return numbers;
    }

    private BusinessException invalid() {
        return new BusinessException("Не удалось прочитать структуру STL. Экспортируйте модель в STL заново.");
    }
}
