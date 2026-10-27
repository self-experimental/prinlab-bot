package ru.printlab.order;

import java.util.UUID;

public record DeliveryDetails(UUID orderId, String method, String fullName, String phone, String address,
                              UUID pricedQuoteId) {
    public String label() {
        if (method.equals("COURIER")) {
            return "СДЭК, курьером до двери";
        }
        return "СДЭК, в пункт выдачи";
    }

    public String summary() {
        return label() + "\nПолучатель: " + fullName + "\nТелефон: " + phone + "\nАдрес: " + address;
    }
}
