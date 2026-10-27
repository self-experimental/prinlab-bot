package ru.printlab.order;

import ru.printlab.common.BusinessException;

public enum OrderStatus {
    DRAFT("Черновик"), MODELING("Моделирование по фото"), ESTIMATING("Проверяем модель"), REVIEW_REQUIRED("Согласование оператором"),
    QUOTE_READY("Цена готова"), AWAITING_PAYMENT("Ожидает оплаты"), PAID("Оплачен"),
    PRINTING("В работе"), QUALITY_CHECK("Проверяем качество"), READY("Готов к отправке"), SHIPPED("Отправлен"),
    COMPLETED("Выполнен"), CANCELLED("Отменён"), EXPIRED("Срок расчёта истёк"), REFUNDED("Деньги возвращены");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public void require(OrderStatus... allowed) {
        for (OrderStatus value : allowed) {
            if (this == value) {
                return;
            }
        }
        throw new BusinessException("Это действие недоступно в статусе «" + label + "».");
    }

    public void requireProductionTransition(OrderStatus next) {
        boolean valid = switch (this) {
            case PAID -> next == PRINTING;
            case PRINTING -> next == QUALITY_CHECK || next == READY;
            case QUALITY_CHECK -> next == READY || next == PRINTING;
            case READY -> next == SHIPPED || next == COMPLETED;
            case SHIPPED -> next == COMPLETED;
            default -> false;
        };
        if (!valid) {
            throw new BusinessException("Недопустимый переход: " + label + " → " + next.label + ".");
        }
    }
}
