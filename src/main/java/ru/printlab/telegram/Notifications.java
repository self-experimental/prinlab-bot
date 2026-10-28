package ru.printlab.telegram;

import org.springframework.stereotype.Service;
import ru.printlab.order.PrintOrder;
import ru.printlab.order.OrderDao;
import java.util.UUID;
import ru.printlab.order.Quote;
import ru.printlab.order.OrderStatus;
import ru.printlab.order.DeliveryDao;
import ru.printlab.order.DeliveryDetails;
import ru.printlab.pricing.PublicPrice;
import ru.printlab.common.Money;
import ru.printlab.operator.OperatorAccess;

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
    private final Clock clock;
    private final OrderDao orders;
    private final OperatorAccess operators;
    private final DeliveryDao deliveries;

    public Notifications(OutboxDao outbox, Clock clock, OrderDao orders,
                         OperatorAccess operators, DeliveryDao deliveries) {
        this.outbox = outbox;
        this.clock = clock;
        this.orders = orders;
        this.operators = operators;
        this.deliveries = deliveries;
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
        orderButtons(chat, text, buttons, null);
    }

    public void orderButtons(long chat, String text, List<Map<String, String>> buttons, UUID orderId) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (Map<String, String> button : buttons) {
            rows.add(List.of(button));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chat);
        payload.put("text", text);
        payload.put("reply_markup", Map.of("inline_keyboard", rows));
        outbox.add("sendMessage", payload, clock.instant(), orderId);
    }

    public void orderText(long chat, String text, UUID orderId) {
        outbox.add("sendMessage", Map.of("chat_id", chat, "text", text), clock.instant(), orderId);
    }

    public void adminButtons(PrintOrder order, String text, List<Map<String, String>> buttons) {
        for (long chat : operators.recipients()) orderButtons(chat, text, buttons, order.id());
    }

    public void adminOrder(PrintOrder order, String text) {
        for (long chat : operators.recipients()) {
            operatorOrder(chat, order, text);
        }
    }

    public void operatorOrder(long chat, PrintOrder order, String text) {
        List<Map<String, String>> buttons = new ArrayList<>();
        buttons.add(button("Ответить клиенту", "op:reply:" + order.id()));
        buttons.add(button("Отправить фото", "op:photo:" + order.id()));
        if (order.status() == OrderStatus.MODELING) {
            buttons.add(button("Готовый STL", "op:stl:" + order.id()));
        }
        buttons.add(button("Изменить статус", "op:status:" + order.id()));
        buttons.add(button("Открыть заказ", "op:open:" + order.id()));
        if (order.status() == OrderStatus.REVIEW_REQUIRED && order.quoteId() != null) {
            buttons.add(button("Подтвердить цену", "op:publish:" + order.id()));
        }
        if (order.quoteId() != null && order.paidAt() == null && !order.deleted()
                && List.of(OrderStatus.REVIEW_REQUIRED, OrderStatus.QUOTE_READY, OrderStatus.AWAITING_PAYMENT).contains(order.status()))
            buttons.add(button("Изменить цену печати", "op:price:" + order.id()));
        DeliveryDetails delivery = deliveries.get(order.id());
        if (delivery != null && !delivery.method().equals("SELF_PICKUP") && (order.status() == OrderStatus.QUOTE_READY || order.status() == OrderStatus.REVIEW_REQUIRED)) {
            buttons.add(button("Указать стоимость доставки", "op:delivery:" + order.id()));
        }
        if (order.paidAt() == null && order.status() != OrderStatus.CANCELLED) buttons.add(button("Отменить заказ", "op:cancel:" + order.id()));
        buttons.add(button("Удалить заказ", "op:delete:" + order.id()));
        orderButtons(chat, text, buttons, order.id());
    }

    public void removeKeyboard(long chat, String text) {
        outbox.add("sendMessage", Map.of("chat_id", chat, "text", text,
                "reply_markup", Map.of("remove_keyboard", true)), clock.instant());
    }

    public void keyboard(long chat, String text, List<List<String>> buttons) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (List<String> row : buttons) {
            List<Map<String, String>> keys = new ArrayList<>();
            for (String label : row) {
                keys.add(Map.of("text", label));
            }
            rows.add(keys);
        }
        outbox.add("sendMessage", Map.of("chat_id", chat, "text", text,
                "reply_markup", Map.of("keyboard", rows, "resize_keyboard", true, "is_persistent", true)), clock.instant());
    }

    public static Map<String, String> button(String text, String callback) {
        return Map.of("text", text, "callback_data", callback);
    }

    public void admins(String text) {
        for (long admin : operators.recipients()) {
            text(admin, text);
        }
    }

    public void fileToAdmins(String fileId, String caption) {
        for (long admin : operators.recipients()) {
            outbox.add("sendDocument", Map.of("chat_id", admin, "document", fileId, "caption", caption), clock.instant());
        }
    }

    public void fileToAdmins(PrintOrder order, String fileId, String caption) {
        for (long chat : operators.recipients()) {
            outbox.add("sendDocument", Map.of("chat_id", chat, "document", fileId, "caption", caption), clock.instant(), order.id());
        }
    }

    public void document(UUID customer, String fileId, String caption) {
        reference(orders.telegramId(customer), fileId, true, caption);
    }

    public void referenceToAdmins(String fileId, boolean document, String caption) {
        for (long admin : operators.recipients()) {
            reference(admin, fileId, document, caption);
        }
    }

    public void referenceToAdmins(PrintOrder order, String fileId, boolean document, String caption) {
        for (long chat : operators.recipients()) {
            String method = "sendPhoto";
            String field = "photo";
            if (document) {
                method = "sendDocument";
                field = "document";
            }
            outbox.add(method, Map.of("chat_id", chat, field, fileId, "caption", caption), clock.instant(), order.id());
        }
    }

    public void reference(long chat, String fileId, boolean document, String caption) {
        reference(chat, fileId, document, caption, null);
    }

    public void reference(long chat, String fileId, boolean document, String caption, UUID orderId) {
        if (document) {
            outbox.add("sendDocument", Map.of("chat_id", chat, "document", fileId, "caption", caption), clock.instant(), orderId);
        } else {
            outbox.add("sendPhoto", Map.of("chat_id", chat, "photo", fileId, "caption", caption), clock.instant(), orderId);
        }
    }

    public void receiving(long chat, PrintOrder order) {
        buttons(chat, "Как хочешь получить заказ?\n\nСамовывоз в Москве бесплатный. Доставку ты оплачиваешь СДЭК отдельно от заказа PrintLab: в приложении, на сайте или при получении.", receivingButtons(order));
    }

    private List<Map<String, String>> receivingButtons(PrintOrder order) {
        return List.of(button("Курьером СДЭК", "ship:" + order.id() + ":COURIER"),
                button("В пункт выдачи СДЭК", "ship:" + order.id() + ":PICKUP"),
                button("Бесплатный самовывоз в Москве", "pickupList:" + order.id()),
                button("Написать сообщение", "question:" + order.id()), button("В меню", "nav:home"));
    }

    public void quote(PrintOrder order, Quote quote) {
        DeliveryDetails delivery = deliveries.get(order.id());
        long chat = orders.telegramId(order.customerId());
        if (order.status() == OrderStatus.REVIEW_REQUIRED) {
            String text = "Модель заказа №" + order.number() + " рассчитана.\n\nОжидай подтверждения заказа командой PrintLab. После него можно будет оплатить заказ.";
            if (delivery == null) {
                buttons(chat, text + "\n\nПока выбери, как получить заказ. Самовывоз бесплатный, СДЭК оплачивается отдельно.", receivingButtons(order));
            } else {
                buttons(chat, text + "\n\nДанные для получения у нас.", List.of(
                        button("Изменить способ получения", "shipping:" + order.id()),
                        button("Написать сообщение", "question:" + order.id()), button("В меню", "nav:home")));
            }
            return;
        }
        PublicPrice price = quote.snapshot().publicPrice();
        String expiry = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'")
                .withZone(ZoneId.of("Europe/Moscow")).format(quote.expiresAt());
        String text = "Расчёт заказа №" + order.number() + " готов!\n\n"
                + "Печать: " + Money.format(price.regularPrintPrice()) + "\nСкидка: " + Money.format(price.discount());
        if (price.launchFee().signum() > 0) text += "\nЗапуск: " + Money.format(price.launchFee());
        if (!order.promoCode().isBlank()) text += "\n" + price.promotionNote();
        List<Map<String, String>> buttons = new ArrayList<>();
        if (delivery != null && quote.id().equals(delivery.pricedQuoteId())) {
            if (delivery.method().equals("SELF_PICKUP")) {
                text += "\n\nСамовывоз: " + delivery.address() + "\nСтоимость самовывоза: 0 ₽";
                var point = ru.printlab.order.ReceivingPoints.get(delivery.pickupKey());
                buttons.add(Map.of("text", "ссылка на адрес", "url", point.mapUrl()));
            } else if (delivery.externalPrice() != null) {
                text += "\n\nСДЭК до «" + delivery.address() + "»: ориентировочно " + Money.format(delivery.externalPrice())
                        + "\nДоставку ты оплачиваешь СДЭК отдельно: в приложении, на сайте или при получении. В оплату PrintLab она не входит.";
            } else {
                // Historical offers retain their agreed receipt until explicitly repriced by an operator.
                text += "\nДоставка до «" + delivery.address() + "»: " + Money.format(price.deliveryPrice());
            }
            text += "\n\nК оплате PrintLab: " + Money.format(price.total())
                    + "\n\nПримерный срок готовности сообщим после оплаты."
                    + "\n\nЦена подтверждена командой PrintLab и действует до " + expiry + ".\nВсё подходит? Подтверди заказ.";
            buttons.add(button("Подтвердить заказ", "accept:" + quote.id()));
        } else {
            text += "\n\nВыбери способ получения. Команда PrintLab проверит его перед подтверждением заказа.";
            buttons.addAll(receivingButtons(order));
        }
        buttons.add(button("Задать вопрос", "question:" + order.id()));
        buttons.add(button("Отменить заказ", "cancel:" + order.id()));
        buttons.add(button("В меню", "nav:home"));
        orderButtons(chat, text, buttons, order.id());
    }
}
