package ru.printlab.telegram;

import tools.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;

public final class MediaFiles {
    private static final Set<String> PHOTOS = Set.of("jpg", "jpeg", "jpe", "jfif", "pjpeg", "pjp", "png", "apng",
            "webp", "gif", "bmp", "dib", "tif", "tiff", "heic", "heif", "hif", "avif", "avifs", "jxl", "jp2",
            "j2k", "jpf", "jpx", "jpm", "mj2", "pcx", "tga", "psd", "svg", "dng", "raw", "cr2", "cr3", "nef",
            "nrw", "arw", "srf", "sr2", "orf", "rw2", "pef", "raf", "srw", "rwl", "iiq", "3fr", "fff", "erf",
            "kdc", "dcr", "mos", "mrw", "x3f");

    private MediaFiles() {}

    public static boolean stl(String name) {
        return extension(name).equals("stl");
    }

    public static boolean photo(String name) {
        return PHOTOS.contains(extension(name));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static JsonNode largestPhoto(JsonNode message) {
        JsonNode largest = null;
        for (JsonNode photo : message.path("photo")) {
            if (largest == null || photo.path("width").asLong() * photo.path("height").asLong()
                    > largest.path("width").asLong() * largest.path("height").asLong()) {
                largest = photo;
            }
        }
        return largest;
    }

    public static boolean attachment(JsonNode message) {
        return message.has("photo") || message.has("document") || message.has("animation") || message.has("video")
                || message.has("audio") || message.has("voice") || message.has("video_note") || message.has("sticker");
    }
}
