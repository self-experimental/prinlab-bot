package ru.printlab.telegram;

import org.springframework.stereotype.Service;
import ru.printlab.config.AppProperties;
import ru.printlab.order.PrintOrder;
import ru.printlab.order.OrderDao;
import java.util.UUID;
import ru.printlab.order.Quote;
import ru.printlab.pricing.PublicPrice;
import ru.printlab.common.Money;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class Notifications {
    private final OutboxDao outbox;
    private final AppProperties properties;
    private final Clock clock;
    private final OrderDao orders;

    public Notifications(OutboxDao outbox, AppProperties properties, Clock clock, OrderDao orders) {
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
        this.orders = orders;
    }

    public void text(UUID customer, String text) {
        text(orders.telegramId(customer), text);
    }

    public void buttons(UUID customer, String text, List<Map<String, String>> buttons) {
        buttons(orders.telegramId(customer), text, buttons);
    }

    public void text(long chat, String text) {
        outbox.add("sendMessage", Map.of("chat_id", chat, "text", text), clock.instant());
    }

    public void buttons(long chat, String text, List<Map<String, String>> buttons) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (Map<String, String> button : buttons) {
            rows.add(List.of(button));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chat);
        payload.put("text", text);
        payload.put("reply_markup", Map.of("inline_keyboard", rows));
        outbox.add("sendMessage", payload, clock.instant());
    }

    public static Map<String, String> button(String text, String callback) {
        return Map.of("text", text, "callback_data", callback);
    }

    public void admins(String text) {
        for (long admin : properties.adminIds()) {
            text(admin, text);
        }
    }

    public void fileToAdmins(String fileId, String caption) {
        for (long admin : properties.adminIds()) {
            outbox.add("sendDocument", Map.of("chat_id", admin, "document", fileId, "caption", caption), clock.instant());
        }
    }

    public void quote(PrintOrder order, Quote quote) {
        PublicPrice price = quote.snapshot().publicPrice();
        String expiry = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'")
                .withZone(ZoneId.of("Europe/Moscow")).format(quote.expiresAt());
        String launchLabel = "Запуск заказа";
        if (!quote.snapshot().rates().launchFeePerOrder()) {
            launchLabel = "Запуски (по числу пластин)";
        }
        String text = "Предложение по заказу №" + order.number() + "\n"
                + "Печать: " + Money.format(price.regularPrintPrice()) + "\n"
                + launchLabel + ": " + Money.format(price.launchFee()) + "\n"
                + "Скидка: " + Money.format(price.discount()) + "\n"
                + "Доставка: " + Money.format(price.deliveryPrice()) + "\n"
                + "Итого: " + Money.format(price.total()) + "\n"
                + "Оценка машинного времени на весь тираж: " + quote.snapshot().estimate().printMinutes().stripTrailingZeros().toPlainString() + " мин.\n"
                + "Пластин: " + quote.snapshot().estimate().plateCount() + ".\n"
                + "Очередь и срок выдачи согласуются отдельно.\n"
                + price.promotionNote() + "\nДействует до " + expiry
                + ". Подтверждение фиксирует параметры; оплатите до этого срока.";
        buttons(order.customerId(), text, List.of(button("Подтвердить заказ", "accept:" + quote.id()),
                button("Отменить", "cancel:" + order.id())));
    }
}
