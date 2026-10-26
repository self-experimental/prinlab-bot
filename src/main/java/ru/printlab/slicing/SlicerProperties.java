package ru.printlab.slicing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;
import java.util.Map;

@ConfigurationProperties("printlab.slicer")
public record SlicerProperties(boolean enabled, boolean profileConfirmed, String executable,
                               Path profilesDir, Path workDir, String version,
                               int timeoutSeconds, Map<String, String> materialProfiles, SlicerType engine) {
    @ConstructorBinding
    public SlicerProperties {
        if (engine == null) {
            engine = SlicerType.BAMBU_STUDIO;
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalStateException("Тайм-аут слайсера должен быть от 1 до 600 секунд");
        }
        if (enabled && (executable == null || executable.isBlank() || version == null || version.isBlank()
                || version.equals("unconfigured"))) {
            throw new IllegalStateException("Задайте executable и версию автоматического слайсера");
        }
    }

    public SlicerProperties(boolean enabled, boolean profileConfirmed, String executable,
                            Path profilesDir, Path workDir, String version,
                            int timeoutSeconds, Map<String, String> materialProfiles) {
        this(enabled, profileConfirmed, executable, profilesDir, workDir, version, timeoutSeconds,
                materialProfiles, SlicerType.ORCA);
    }
}
