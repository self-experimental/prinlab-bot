package ru.printlab.order;

import java.math.BigDecimal;
import java.util.UUID;

public record DeliveryDetails(UUID orderId, String method, String fullName, String phone, String address,
                              UUID pricedQuoteId, BigDecimal externalPrice, String pickupKey) {
    public DeliveryDetails(UUID orderId, String method, String fullName, String phone, String address, UUID pricedQuoteId) {
        this(orderId, method, fullName, phone, address, pricedQuoteId, null, null);
    }

    public String label() {
        return switch (method) {
            case "COURIER" -> "СДЭК, курьером до двери";
            case "SELF_PICKUP" -> "Бесплатный самовывоз в Москве";
            default -> "СДЭК, в пункт выдачи";
        };
    }

    public String summary() {
        String text = label();
        if (fullName != null && !fullName.isBlank()) text += "\nПолучатель: " + fullName;
        if (phone != null && !phone.isBlank()) text += "\nТелефон: " + phone;
        text += "\nАдрес: " + address;
        if (!method.equals("SELF_PICKUP")) {
            if (externalPrice != null) text += "\nТариф СДЭК: " + ru.printlab.common.Money.format(externalPrice);
            text += "\nДоставка оплачивается СДЭК отдельно от заказа PrintLab.";
        }
        return text;
    }
}
