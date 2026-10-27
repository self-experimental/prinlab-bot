package ru.printlab.promotion;

public enum PromoType {
    USER_LIMIT("Первые пользователи"), DATE("По датам"), COMBINED("Пользователи и даты"),
    FIRST_ORDER("Первый заказ"), SUBSEQUENT_ORDERS("Заказы после первого");
    private final String label;
    PromoType(String label) { this.label = label; }
    public String label() { return label; }
}
