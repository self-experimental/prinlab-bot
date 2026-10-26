package ru.printlab.storage;

import org.springframework.stereotype.Service;
import ru.printlab.config.AppProperties;
import ru.printlab.config.PrinterProfile;
import ru.printlab.common.BusinessException;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ModelStorage {
    private final Path root;
    private final StlValidator validator;
    private final PrinterProfile printer;

    public ModelStorage(AppProperties properties, StlValidator validator) {
        this.root = properties.storageDir().toAbsolutePath().normalize();
        this.validator = validator;
        this.printer = properties.printer();
    }

    public ModelFile save(UUID customer, String fileId, String name, byte[] bytes) {
        ModelDimensions dimensions = validator.inspect(name, bytes);
        if (dimensions.widthMm() > printer.widthMm().doubleValue()
                || dimensions.depthMm() > printer.depthMm().doubleValue()
                || dimensions.heightMm() > printer.heightMm().doubleValue()) {
            throw new BusinessException("Модель в текущей ориентации больше рабочей области A1 mini 180×180×180 мм. "
                    + "Подготовьте подходящую ориентацию или разделите модель. Масштаб автоматически не меняем.");
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String storageName = hash + ".stl";
            Files.createDirectories(root);
            Path target = root.resolve(storageName);
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(root, "upload-", ".tmp");
                try {
                    Files.write(temporary, bytes);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException ignored) {
                    // A repeated upload already has the same content-addressed destination.
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            String safeName = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
            if (safeName.length() > 255) {
                safeName = safeName.substring(0, 251) + ".stl";
            }
            return new ModelFile(UUID.randomUUID(), customer, fileId, safeName, hash, storageName, bytes.length);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Не удалось сохранить STL в хранилище");
        }
    }
}
