package ru.printlab.telegram;

import org.springframework.stereotype.Component;
import ru.printlab.catalog.ColorService;
import ru.printlab.catalog.FilamentColor;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.common.Money;
import ru.printlab.order.*;
import ru.printlab.storage.StlValidator;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static ru.printlab.telegram.Notifications.button;

@Component
public class OperatorPanel {
    private static final List<String> COLOR_FIELDS = List.of("name", "surface", "manufacturer", "palette", "article");
    private final OrderService service;
    private final OrderDao orders;
    private final DeliveryDao deliveries;
    private final ModelingService modeling;
    private final ColorService colors;
    private final DialogDao dialogs;
    private final Notifications messages;
    private final TelegramGateway telegram;
    private final Json json;
    private final PromotionPanel promotions;

    public OperatorPanel(OrderService service, OrderDao orders, DeliveryDao deliveries, ModelingService modeling,
                         ColorService colors, DialogDao dialogs, Notifications messages, TelegramGateway telegram, Json json, PromotionPanel promotions) {
        this.service = service;
        this.orders = orders;
        this.deliveries = deliveries;
        this.modeling = modeling;
        this.colors = colors;
        this.dialogs = dialogs;
        this.messages = messages;
        this.telegram = telegram;
        this.json = json;
        this.promotions = promotions;
    }

    public void menu(long user, long chat) {
        service.requireAdmin(user);
        dialogs.clear(user, chat);
        messages.buttons(chat, "Админ-меню PrintLab\n\nВыберите заказ, чтобы ответить клиенту, передать модель или изменить статус.", List.of(
                button("Заказы", "op:orders"), button("Цвета PLA", "cat:list:0"),
                button("Промокоды", "pc:list:0"), button("Удалённые заказы", "op:deleted")));
    }

    public void list(long user, long chat) {
        service.requireAdmin(user);
        dialogs.clear(user, chat);
        List<Map<String, String>> buttons = new ArrayList<>();
        for (PrintOrder order : orders.list(user, true)) {
            buttons.add(button("№" + order.number() + " · " + order.status().label(), "op:open:" + order.id()));
        }
        buttons.add(button("Цвета PLA", "cat:list:0"));
        buttons.add(button("Назад", "op:menu"));
        messages.buttons(chat, "Последние заказы", buttons);
    }

    public void show(long user, long chat, UUID id) {
        service.requireAdmin(user);
        dialogs.clear(user, chat);
        PrintOrder order = orders.get(id);
        if (order.deleted()) {
            messages.buttons(chat, "Удалённый заказ №" + order.number() + "\nСтатус: " + order.status().label(), List.of(
                    button("Восстановить", "op:restore:" + id), button("Назад", "op:deleted")));
            return;
        }
        String text = "Заказ №" + order.number() + "\n\nСтатус: " + order.status().label();
        if (order.modelId() != null) {
            text += "\n\nФайл: " + orders.model(order.modelId()).name() + "\n" + service.summary(order);
        }
        DeliveryDetails delivery = deliveries.get(id);
        if (delivery != null) {
            text += "\n\n" + delivery.summary();
        }
        if (order.quoteId() != null) {
            text += "\n\n" + service.internalSummary(order, orders.quote(order.quoteId()));
        }
        messages.operatorOrder(chat, order, text);
        if (order.status() == OrderStatus.MODELING) {
            modeling.show(user, chat, order);
        }
    }

    public void callback(long user, long chat, String data) {
        service.requireAdmin(user);
        String[] parts = data.split(":", 3);
        if (parts.length < 2) {
            throw stale();
        }
        if (parts[0].equals("pc")) { promotions.callback(user, chat, data); return; }
        if (parts[0].equals("cat")) {
            catalog(user, chat, parts);
            return;
        }
        if (!parts[0].equals("op")) {
            throw stale();
        }
        switch (parts[1]) {
            case "menu", "cancelInput" -> { menu(user, chat); return; }
            case "orders" -> { list(user, chat); return; }
            case "deleted" -> {
                dialogs.clear(user, chat);
                List<Map<String,String>> buttons = new ArrayList<>();
                for (PrintOrder deleted : orders.deletedOrders()) buttons.add(button("№" + deleted.number() + " · " + deleted.status().label(), "op:open:" + deleted.id()));
                buttons.add(button("Назад", "op:menu"));
                messages.buttons(chat, "Удалённые заказы. Финансовая история сохранена.", buttons);
                return;
            }
        }
        if (parts.length != 3) {
            throw stale();
        }
        UUID id = UUID.fromString(parts[2]);
        PrintOrder order = orders.get(id);
        if (order.deleted() && !List.of("open", "restore", "deleteYes").contains(parts[1]))
            throw new BusinessException("Заказ удалён. Сначала восстановите его.");
        switch (parts[1]) {
            case "open" -> show(user, chat, id);
            case "reply" -> {
                dialogs.save(user, chat, "OP_REPLY", null, id, null);
                prompt(chat, "Напишите сообщение или пришлите фото клиенту по заказу №" + order.number() + ".", id);
            }
            case "stl" -> {
                order.status().require(OrderStatus.MODELING);
                dialogs.save(user, chat, "OP_STL", null, id, null);
                prompt(chat, "Пришлите готовый STL файлом для заказа №" + order.number() + ".", id);
            }
            case "photo" -> {
                if (order.deleted()) throw new BusinessException("Заказ удалён. Сначала восстановите его.");
                dialogs.save(user, chat, "OP_PHOTO", null, id, null);
                prompt(chat, "Пришлите фото клиенту по заказу №" + order.number() + ". Можно отправить фотографию или файл изображения.", id);
            }
            case "cancel" -> messages.buttons(chat, "Отменить заказ №" + order.number() + "? Клиент получит уведомление.", List.of(
                    button("Да, отменить", "op:cancelYes:" + id), button("Назад", "op:open:" + id)));
            case "cancelYes" -> { service.cancel(user, id); messages.text(chat, "Заказ отменён."); show(user, chat, id); }
            case "delete" -> messages.buttons(chat, "Удалить заказ №" + order.number() + " из списка? Его можно восстановить в разделе «Удалённые заказы».", List.of(
                    button("Удалить", "op:deleteYes:" + id), button("Назад", "op:open:" + id)));
            case "deleteYes" -> { service.delete(user, id); dialogs.clear(user, chat); show(user, chat, id); }
            case "restore" -> { service.restore(user, id); show(user, chat, id); }
            case "status" -> statuses(chat, order);
            case "paid" -> {
                order.status().require(OrderStatus.AWAITING_PAYMENT);
                dialogs.save(user, chat, "PAYMENT_CONFIRM", null, id, order.quoteId().toString());
                messages.buttons(chat, "Подтвердить оплату заказа №" + order.number() + " на сумму "
                        + Money.format(orders.quote(order.quoteId()).snapshot().publicPrice().total())
                        + "?\n\nНажимайте только после фактического поступления денег.", List.of(
                        button("Деньги поступили", "op:payConfirm:" + id), button("Назад", "op:open:" + id)));
            }
            case "payConfirm" -> {
                BotDialog dialog = expected(user, chat, "PAYMENT_CONFIRM", id);
                requireQuote(order, dialog.payload());
                dialogs.save(user, chat, "PAYMENT", null, id, dialog.payload());
                prompt(chat, "Введите уникальный идентификатор поступления из банка.\n\nОт 3 до 128 латинских букв, цифр или символов _ . / : -.", id);
            }
            case "delivery" -> {
                order.status().require(OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
                DeliveryDetails delivery = deliveries.get(id);
                if (delivery == null) {
                    throw new BusinessException("Клиент пока не прислал данные доставки.");
                }
                dialogs.save(user, chat, "DELIVERY_PRICE", null, id, order.quoteId().toString());
                prompt(chat, "Заказ №" + order.number() + "\n\n" + delivery.summary()
                        + "\n\nВведите стоимость СДЭК в рублях. Эта сумма добавится к заказу без наценки и скидки.", id);
            }
            case "approve" -> {
                order.status().require(OrderStatus.REVIEW_REQUIRED);
                dialogs.save(user, chat, "APPROVE", null, id, order.quoteId().toString());
                prompt(chat, "Доход заказа №" + order.number() + " ниже финансового порога.\n\nУкажите причину согласования, от 5 до 500 символов. После ввода клиент получит расчёт.", id);
            }
            case "to_PRINTING", "to_READY", "to_SHIPPED", "to_COMPLETED" -> {
                OrderStatus next = OrderStatus.valueOf(parts[1].substring(3));
                service.production(user, order.number(), next);
                messages.text(chat, "Статус заказа обновлён.");
                show(user, chat, id);
            }
            default -> throw stale();
        }
    }

    private void statuses(long chat, PrintOrder order) {
        List<Map<String, String>> buttons = new ArrayList<>();
        switch (order.status()) {
            case AWAITING_PAYMENT -> buttons.add(button("Оплачен", "op:paid:" + order.id()));
            case PAID -> buttons.add(button("В работе", "op:to_PRINTING:" + order.id()));
            case PRINTING, QUALITY_CHECK -> buttons.add(button("Готов к отправке", "op:to_READY:" + order.id()));
            case READY -> {
                buttons.add(button("Отправлен", "op:to_SHIPPED:" + order.id()));
                buttons.add(button("Выполнен", "op:to_COMPLETED:" + order.id()));
            }
            case SHIPPED -> buttons.add(button("Выполнен", "op:to_COMPLETED:" + order.id()));
            default -> { }
        }
        buttons.add(button("Назад", "op:open:" + order.id()));
        String text = "Заказ №" + order.number() + "\n\nТекущий статус: " + order.status().label();
        if (buttons.size() == 1) {
            text += "\n\nСледующий статус пока недоступен. Ожидание оплаты появится после подтверждения итоговой цены клиентом.";
        } else {
            text += "\n\nВыберите следующий статус. Клиент получит уведомление.";
        }
        messages.orderButtons(chat, text, buttons, order.id());
    }

    public boolean input(long user, long chat, JsonNode message) {
        service.requireAdmin(user);
        String text = message.path("text").asText("").trim();
        BotDialog dialog = dialogs.get(user, chat);
        UUID replyOrder = dialogs.replyOrder(chat, message.path("reply_to_message").path("message_id").asLong());
        if (replyOrder != null && chat < 0 && dialog == null && !text.startsWith("/")) {
            sendReply(user, chat, replyOrder, message, false);
            return true;
        }
        if (dialog == null) {
            return false;
        }
        if (dialog.kind().equals("PROMO_EDIT")) return promotions.input(user, chat, text);
        if (dialog.kind().equals("OP_REPLY") || dialog.kind().equals("OP_PHOTO")) {
            if ("album".equals(dialog.step()) && !message.path("media_group_id").asText("").equals(dialog.payload())) {
                dialogs.clear(user, chat);
                return false;
            }
            sendReply(user, chat, dialog.orderId(), message, dialog.kind().equals("OP_PHOTO"));
            return true;
        }
        if (!List.of("COLOR", "OP_STL", "PAYMENT", "DELIVERY_PRICE", "APPROVE").contains(dialog.kind())) {
            return false;
        }
        if (dialog.kind().equals("COLOR")) {
            if (text.isBlank()) {
                throw new BusinessException("Введите значение текстом или нажмите «Пропустить».");
            }
            colorInput(user, chat, dialog, text);
            return true;
        }
        if (dialog.kind().equals("OP_STL")) {
            JsonNode file = message.path("document");
            String name = file.path("file_name").asText("");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".stl") || file.path("file_size").asLong() > StlValidator.MAX_BYTES) {
                throw new BusinessException("Пришлите STL-файл размером до 20 МБ.");
            }
            modeling.attach(user, orders.get(dialog.orderId()).number(), file.path("file_id").asText(), name,
                    telegram.download(file.path("file_id").asText()));
            dialogs.clear(user, chat);
            messages.text(chat, "Модель передана клиенту.");
            return true;
        }
        if (text.isBlank()) {
            throw new BusinessException("Для этого шага нужно текстовое сообщение.");
        }
        PrintOrder order = orders.get(dialog.orderId());
        switch (dialog.kind()) {
            case "OP_REPLY" -> {
                modeling.reply(user, order.number(), text);
                messages.text(chat, "Сообщение отправлено клиенту.");
            }
            case "PAYMENT" -> {
                requireQuote(order, dialog.payload());
                service.paid(user, order.number(), orders.quote(order.quoteId()).snapshot().publicPrice().total(), text);
                messages.text(chat, "Оплата учтена.");
            }
            case "DELIVERY_PRICE" -> {
                BigDecimal amount;
                try {
                    amount = new BigDecimal(text.replace(',', '.'));
                } catch (NumberFormatException exception) {
                    throw new BusinessException("Не получилось распознать число. Попробуй ввести его ещё раз.");
                }
                service.priceDelivery(user, order.id(), UUID.fromString(dialog.payload()), amount);
                messages.text(chat, "Стоимость доставки сохранена.");
            }
            case "APPROVE" -> {
                requireQuote(order, dialog.payload());
                service.approve(user, order.number(), text);
                messages.text(chat, "Финансовое согласование сохранено.");
            }
            default -> { return false; }
        }
        dialogs.clear(user, chat);
        return true;
    }

    private void sendReply(long user, long chat, UUID id, JsonNode message, boolean photoOnly) {
        String text = message.path("text").asText("").trim();
        if (!photoOnly && !text.isBlank() && !MediaFiles.attachment(message)) {
            modeling.reply(user, orders.get(id).number(), text);
            dialogs.clear(user, chat);
            messages.text(chat, "Сообщение отправлено клиенту.");
            return;
        }
        JsonNode photo = MediaFiles.largestPhoto(message);
        JsonNode file = message.path("document");
        if (photo != null) {
            modeling.replyPhoto(user, id, photo.path("file_id").asText(), false, message.path("caption").asText(""));
        } else if (message.has("document") && MediaFiles.photo(file.path("file_name").asText(""))) {
            if (file.path("file_size").asLong() > StlValidator.MAX_BYTES) throw new BusinessException("Фото должно быть размером до 20 МБ.");
            modeling.replyPhoto(user, id, file.path("file_id").asText(), true, message.path("caption").asText(""));
        } else {
            throw new BusinessException("Для отправки фото нужен файл изображения или фотография. Готовый STL отправляйте через кнопку «Готовый STL».");
        }
        if (message.has("media_group_id")) dialogs.save(user, chat, "OP_PHOTO", "album", id, message.path("media_group_id").asText());
        else dialogs.clear(user, chat);
        messages.text(chat, "Фото отправлено клиенту.");
    }

    private void catalog(long user, long chat, String[] parts) {
        switch (parts[1]) {
            case "list" -> {
                dialogs.clear(user, chat);
                int page = Integer.parseInt(parts[2]);
                List<FilamentColor> list = colors.page(false, page);
                List<Map<String, String>> buttons = new ArrayList<>();
                for (int index = 0; index < Math.min(5, list.size()); index++) {
                    FilamentColor color = list.get(index);
                    String label = color.label();
                    if (!color.available()) {
                        label += " · скрыт";
                    }
                    buttons.add(button(label, "cat:open:" + color.id()));
                }
                if (page > 0) {
                    buttons.add(button("Предыдущая", "cat:list:" + (page - 1)));
                }
                if (list.size() > 5) {
                    buttons.add(button("Следующая", "cat:list:" + (page + 1)));
                }
                buttons.add(button("Добавить цвет", "cat:new"));
                buttons.add(button("Назад", "op:menu"));
                messages.buttons(chat, "Цвета PLA", buttons);
            }
            case "new" -> {
                dialogs.save(user, chat, "COLOR", "name", null, "{}");
                colorPrompt(chat, "name");
            }
            case "skip" -> {
                BotDialog dialog = dialogs.get(user, chat);
                if (dialog == null || !dialog.kind().equals("COLOR") || dialog.step().equals("name")) {
                    throw stale();
                }
                colorInput(user, chat, dialog, "");
            }
            case "open", "edit", "toggle", "delete", "deleteYes" -> {
                UUID id = UUID.fromString(parts[2]);
                FilamentColor color = colors.get(id);
                switch (parts[1]) {
                    case "open" -> colorCard(chat, color);
                    case "edit" -> {
                        Map<String, String> values = new LinkedHashMap<>();
                        values.put("id", id.toString());
                        values.put("available", Boolean.toString(color.available()));
                        dialogs.save(user, chat, "COLOR", "name", null, json.write(values));
                        messages.text(chat, "Текущий цвет: " + color.label() + ". Введите поля заново. Неизвестные поля можно пропустить.");
                        colorPrompt(chat, "name");
                    }
                    case "toggle" -> {
                        colors.toggle(user, id);
                        messages.text(chat, "Цвет сохранён.");
                        colorCard(chat, colors.get(id));
                    }
                    case "delete" -> messages.buttons(chat, "Удалить цвет «" + color.label() + "» из каталога? История заказов сохранится.",
                            List.of(button("Удалить", "cat:deleteYes:" + id), button("Назад", "cat:open:" + id)));
                    case "deleteYes" -> {
                        colors.delete(user, id);
                        messages.buttons(chat, "Цвет удалён.", List.of(button("Назад", "cat:list:0")));
                    }
                }
            }
            default -> throw stale();
        }
    }

    private void colorCard(long chat, FilamentColor color) {
        String text = "Цвет: " + color.name() + "\nПоверхность: " + display(color.surface())
                + "\nПроизводитель: " + display(color.manufacturer()) + "\nНомер в палитре: " + display(color.paletteNumber())
                + "\nАртикул Bambu: " + display(color.bambuArticle());
        String availability = "Скрыть";
        if (!color.available()) {
            availability = "Сделать доступным";
        }
        messages.buttons(chat, text, List.of(button("Изменить", "cat:edit:" + color.id()),
                button(availability, "cat:toggle:" + color.id()), button("Удалить", "cat:delete:" + color.id()),
                button("Назад", "cat:list:0")));
    }

    private void colorInput(long user, long chat, BotDialog dialog, String text) {
        JsonNode source = json.read(dialog.payload(), JsonNode.class);
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("id", "available", "name", "surface", "manufacturer", "palette", "article")) {
            if (source.has(key)) {
                values.put(key, source.path(key).asText());
            }
        }
        int maximum = switch (dialog.step()) {
            case "name", "palette" -> 80;
            case "surface" -> 40;
            case "manufacturer" -> 120;
            case "article" -> 160;
            default -> throw stale();
        };
        if (text.length() > maximum || (dialog.step().equals("name") && text.isBlank())) {
            throw new BusinessException("В этом поле нужно от 1 до " + maximum + " символов.");
        }
        values.put(dialog.step(), text);
        int next = COLOR_FIELDS.indexOf(dialog.step()) + 1;
        if (next < COLOR_FIELDS.size()) {
            String field = COLOR_FIELDS.get(next);
            dialogs.save(user, chat, "COLOR", field, null, json.write(values));
            colorPrompt(chat, field);
            return;
        }
        UUID id = null;
        boolean available = true;
        if (values.containsKey("id")) {
            id = UUID.fromString(values.get("id"));
            available = Boolean.parseBoolean(values.get("available"));
        }
        FilamentColor color = colors.save(user, new FilamentColor(id, values.get("name"), nullable(values.get("surface")),
                nullable(values.get("manufacturer")), nullable(values.get("palette")), nullable(values.get("article")), available, false));
        dialogs.clear(user, chat);
        messages.text(chat, "Цвет сохранён.");
        colorCard(chat, color);
    }

    private void colorPrompt(long chat, String field) {
        String text = switch (field) {
            case "name" -> "Введите название цвета, до 80 символов.";
            case "surface" -> "Укажите поверхность: матовая, глянцевая или другая. До 40 символов.";
            case "manufacturer" -> "Укажите производителя, до 120 символов.";
            case "palette" -> "Укажите номер цвета в палитре, до 80 символов.";
            case "article" -> "Укажите артикул Bambu, если есть. До 160 символов.";
            default -> throw stale();
        };
        List<Map<String, String>> buttons = new ArrayList<>();
        if (!field.equals("name")) {
            buttons.add(button("Пропустить", "cat:skip"));
        }
        buttons.add(button("Отмена", "cat:list:0"));
        messages.buttons(chat, text, buttons);
    }

    private void prompt(long chat, String text, UUID orderId) {
        messages.buttons(chat, text, List.of(button("Назад к заказу", "op:open:" + orderId)));
    }

    private BotDialog expected(long user, long chat, String kind, UUID orderId) {
        BotDialog dialog = dialogs.get(user, chat);
        if (dialog == null || !dialog.kind().equals(kind) || !orderId.equals(dialog.orderId())) {
            throw stale();
        }
        return dialog;
    }

    private void requireQuote(PrintOrder order, String expected) {
        if (order.quoteId() == null || !order.quoteId().toString().equals(expected)) {
            throw stale();
        }
    }

    private String display(String value) {
        if (value == null || value.isBlank()) {
            return "не указано";
        }
        return value;
    }

    private String nullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private BusinessException stale() {
        return new BusinessException("Эта кнопка уже неактуальна. Открой заказ заново.");
    }
}
