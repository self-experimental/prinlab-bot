package ru.printlab.slicing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Map;

@ConfigurationProperties("printlab.slicer.backend")
public record BackendSlicerProperties(URI url, Map<String, String> materials) {
    public BackendSlicerProperties {
        if (url == null || url.getHost() == null || url.getUserInfo() != null
                || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))) {
            throw new IllegalStateException("Задайте HTTP(S) URL backend слайсера");
        }
        if (materials == null) {
            materials = Map.of();
        }
    }
}
