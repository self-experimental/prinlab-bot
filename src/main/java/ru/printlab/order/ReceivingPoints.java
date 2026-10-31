package ru.printlab.order;

import ru.printlab.common.BusinessException;
import java.util.List;

public final class ReceivingPoints {
    public record Point(String key, String label, String address, String mapUrl) {
        public String location() {
            return switch (key) {
                case "baumanskaya" -> "на Бауманской";
                case "seligerskaya" -> "на Селигерской";
                case "planernaya" -> "на Планерной";
                default -> throw new IllegalArgumentException("Unknown pickup point");
            };
        }
    }
    public static final List<Point> ALL = List.of(
            new Point("baumanskaya", "Метро Бауманская", "2-я Бауманская ул., 5, стр. 11, Москва (вторая проходная ГЗ МГТУ им. Баумана)", "https://yandex.ru/maps/-/CXbkiA1S"),
            new Point("seligerskaya", "Метро Селигерская", "Селигерская улица, 14к1, Москва", "https://yandex.ru/maps/-/CXbkqSpC"),
            new Point("planernaya", "Метро Планерная", "улица Соловьиная Роща, 8к1", "https://yandex.ru/maps/-/CXbkmC98"));
    private ReceivingPoints() { }
    public static Point get(String key) {
        for (Point point : ALL) if (point.key().equals(key)) return point;
        throw new BusinessException("Выбери адрес самовывоза заново.");
    }
}
