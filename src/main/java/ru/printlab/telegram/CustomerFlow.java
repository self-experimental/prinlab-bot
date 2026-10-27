package ru.printlab.telegram;

import org.springframework.stereotype.Component;
import ru.printlab.catalog.ColorService;
import ru.printlab.catalog.FilamentColor;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.common.Money;
import ru.printlab.order.*;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ru.printlab.telegram.Notifications.button;

@Component
public class CustomerFlow {
    private final OrderService service;
    private final OrderDao orders;
    private final ModelingService modeling;
    private final ColorService colors;
    private final Notifications messages;
    private final DialogDao dialogs;
    private final Json json;
    private final Clock clock;
    private final IntakeDao intake;

    public CustomerFlow(OrderService service, OrderDao orders, ModelingService modeling, ColorService colors,
                        Notifications messages, DialogDao dialogs, Json json, Clock clock, IntakeDao intake) {
        this.service = service;
        this.orders = orders;
        this.modeling = modeling;
        this.colors = colors;
        this.messages = messages;
        this.dialogs = dialogs;
        this.json = json;
        this.clock = clock;
        this.intake = intake;
    }

    public void home(long user) {
        dialogs.clear(user, user);
        intake.clear(user);
        messages.keyboard(user, BotText.get("home"), List.of(List.of("Новый заказ"), List.of("Мои заказы", "Помощь")));
    }

    public void newOrder(long user) {
        List<PrintOrder> editable = orders.editable(user);
        if (!editable.isEmpty()) {
            PrintOrder current = editable.getFirst();
            IntakeDao.Intake active = intake.get(user);
            if (active != null) {
                for (PrintOrder candidate : editable) if (candidate.id().equals(active.orderId())) current = candidate;
            }
            dialogs.save(user, user, "NEW_CONFIRM", null, current.id(), null);
            messages.buttons(user, "Ты уже создаёшь заказ. Уверен, что хочешь начать новый? Данные незавершённого черновика будут сброшены.", List.of(
                    button("Да, создать новый", "newConfirm:" + current.id()),
                    button("Продолжить текущий заказ", "show:" + current.id()), button("В меню", "nav:home")));
            return;
        }
        newOrderMenu(user);
    }

    private void newOrderMenu(long user) {
        dialogs.save(user, user, "NEW_CHOICE", null, null, null);
        intake.clear(user);
        messages.buttons(user, "Что хочешь заказать?", List.of(button("Рассчитать печать по STL", "nav:print"),
                button("Заказать модель по фото", "nav:model"), button("В меню", "nav:home")));
    }

    private boolean consent(long user, String action) {
        if (orders.consented(user)) {
            return true;
        }
        messages.buttons(user, BotText.get("consent"), List.of(button("Продолжить", "consent:" + action)));
        return false;
    }

    public void print(long user) {
        BotDialog selected = dialogs.get(user, user);
        boolean fresh = selected != null && selected.kind().equals("NEW_CHOICE");
        dialogs.clear(user, user);
        if (!consent(user, "print")) {
            return;
        }
        PrintOrder order = service.newDraft(user, fresh);
        intake.select(user, order.id(), "STL");
        if (order.modelId() != null) {
            draft(user, order);
            return;
        }
        messages.buttons(user, BotText.get("upload.stl"), List.of(button("Назад в меню", "nav:home"),
                button("Нужна помощь", "nav:contact")));
    }

    public void model(long user) {
        BotDialog selected = dialogs.get(user, user);
        boolean fresh = selected != null && selected.kind().equals("NEW_CHOICE");
        dialogs.clear(user, user);
        if (!consent(user, "model")) {
            return;
        }
        PrintOrder request = modeling.request(user, fresh);
        intake.select(user, request.id(), "PHOTO");
        messages.buttons(user, BotText.get("upload.photo"), List.of(button("Назад в меню", "nav:home")));
    }

    public void contact(long user) {
        intake.clear(user);
        dialogs.clear(user, user);
        messages.buttons(user, "Есть вопрос? Напиши нам, поможем разобраться.", List.of(
                Map.of("text", "Написать @oilbanana", "url", "https://t.me/oilbanana"),
                Map.of("text", "Написать @muslimitsuhide", "url", "https://t.me/muslimitsuhide"),
                button("В меню", "nav:home")));
    }

    public void list(long user) {
        intake.clear(user);
        dialogs.clear(user, user);
        List<Map<String, String>> buttons = new ArrayList<>();
        List<PrintOrder> list = orders.list(user, false);
        String text = "Твои заказы";
        if (list.isEmpty()) {
            text = "Заказов пока нет. Можно прислать STL для расчёта или фото для моделирования.";
        }
        for (PrintOrder order : list) {
            buttons.add(button("№" + order.number() + " · " + order.status().label(), "show:" + order.id()));
        }
        buttons.add(button("В меню", "nav:home"));
        messages.buttons(user, text, buttons);
    }

    public void show(long user, UUID id) {
        intake.clear(user);
        dialogs.clear(user, user);
        PrintOrder order = service.owned(user, id);
        if (order.status() == OrderStatus.DRAFT) {
            draft(user, order);
            return;
        }
        if (order.status() == OrderStatus.QUOTE_READY || (order.status() == OrderStatus.REVIEW_REQUIRED && order.quoteId() != null)) {
            messages.quote(order, orders.quote(order.quoteId()));
            return;
        }
        String text = "Заказ №" + order.number() + "\n\nСтатус: " + order.status().label();
        if (order.modelId() != null) {
            text += "\n\nФайл: " + orders.model(order.modelId()).name() + "\n" + service.summary(order);
        }
        if (order.quoteId() != null && (order.paidAt() != null || order.status() == OrderStatus.AWAITING_PAYMENT)) {
            text += "\n\nСогласованная сумма: " + Money.format(orders.quote(order.quoteId()).snapshot().publicPrice().total());
        }
        List<Map<String, String>> buttons = new ArrayList<>();
        buttons.add(button("Написать сообщение", "question:" + id));
        if (order.status() == OrderStatus.MODELING) buttons.add(button("Добавить фото", "photos:" + id));
        if (canCancel(order)) {
            buttons.add(button("Отменить заказ", "cancel:" + id));
        }
        buttons.add(button("Мои заказы", "nav:orders"));
        buttons.add(button("В меню", "nav:home"));
        messages.orderButtons(user, text, buttons, id);
    }

    public void draft(long user, PrintOrder order) {
        order.status().require(OrderStatus.DRAFT);
        intake.select(user, order.id(), "STL");
        dialogs.clear(user, user);
        if (order.modelId() == null) {
            print(user);
            return;
        }
        if (order.colorId() == null) {
            colors(user, order, 0);
            return;
        }
        messages.buttons(user, "Проверь свой заказ №" + order.number() + "\n\nФайл: "
                + orders.model(order.modelId()).name() + "\n" + service.summary(order)
                + "\n\nВсё верно? Можно рассчитать стоимость печати.", List.of(
                button("Рассчитать стоимость", "submit:" + order.id()),
                button("Изменить параметры", "edit:" + order.id()),
                button("Ввести промокод", "input:" + order.id() + ":promo"),
                button("Отменить заказ", "cancel:" + order.id()), button("В меню", "nav:home")));
    }

    public void colors(long user, PrintOrder order, int page) {
        service.owned(user, order.id()).status().require(OrderStatus.DRAFT);
        intake.select(user, order.id(), "STL");
        List<FilamentColor> available = colors.page(true, page);
        List<Map<String, String>> buttons = new ArrayList<>();
        for (int index = 0; index < Math.min(5, available.size()); index++) {
            FilamentColor color = available.get(index);
            // A numeric order number keeps callbacks with a UUID color below Telegram's 64 byte limit.
            buttons.add(button(color.label(), "pick:" + order.number() + ":" + color.id()));
        }
        if (page > 0) {
            buttons.add(button("Предыдущая", "colors:" + order.id() + ":" + (page - 1)));
        }
        if (available.size() > 5) {
            buttons.add(button("Следующая", "colors:" + order.id() + ":" + (page + 1)));
        }
        buttons.add(button("В меню", "nav:home"));
        String text = "Модель получили!\nСейчас печатаем из PLA. Это лёгкий и жёсткий пластик для декора, макетов и бытовых деталей без сильного нагрева.\n\nДоступные цвета";
        if (available.isEmpty()) {
            text += "\nСейчас доступных цветов нет. Напиши нам, согласуем материал.";
            buttons.add(button("Связаться с нами", "nav:contact"));
        }
        messages.buttons(user, text, buttons);
    }

    private void profile(long user, PrintOrder order) {
        messages.buttons(user, "Выбери детализацию печати.\n\nЧем тоньше слой, тем лучше видны мелкие детали и плавнее поверхность, но печать занимает больше времени.", List.of(
                button("Стандартная · 0,20 мм", "profile:" + order.id() + ":STANDARD"),
                button("Повышенная · 0,12 мм", "profile:" + order.id() + ":FINE"),
                button("Другое", "input:" + order.id() + ":layer"),
                button("Назад", "colors:" + order.id() + ":0"), button("В меню", "nav:home")));
    }

    private void infill(long user, PrintOrder order) {
        List<Map<String, String>> buttons = new ArrayList<>();
        for (int percent : List.of(5, 15, 30, 60)) {
            buttons.add(button(percent + "%", "infill:" + order.id() + ":" + percent));
        }
        buttons.add(button("Другое", "input:" + order.id() + ":infill"));
        buttons.add(button("Назад", "step:" + order.id() + ":profile"));
        buttons.add(button("В меню", "nav:home"));
        messages.buttons(user, "Выбери заполнение детали внутри.\n\nБолее плотное заполнение обычно делает деталь жёстче, но требует больше пластика и времени печати.", buttons);
    }

    private void quantity(long user, PrintOrder order) {
        messages.buttons(user, "Сколько деталей напечатать?", List.of(
                button("1", "quantity:" + order.id() + ":1"), button("2", "quantity:" + order.id() + ":2"),
                button("5", "quantity:" + order.id() + ":5"), button("Другое количество", "input:" + order.id() + ":quantity"),
                button("Назад", "step:" + order.id() + ":infill"), button("В меню", "nav:home")));
    }

    public void callback(long user, String data) {
        if (data.startsWith("nav:")) {
            switch (data) {
                case "nav:home" -> home(user);
                case "nav:new" -> newOrder(user);
                case "nav:print" -> print(user);
                case "nav:model" -> model(user);
                case "nav:orders" -> list(user);
                case "nav:contact" -> contact(user);
                default -> throw stale();
            }
            return;
        }
        if (data.startsWith("consent:") || data.equals("agree")) {
            orders.consent(user, clock.instant());
            if (data.equals("consent:model")) {
                model(user);
            } else {
                print(user);
            }
            return;
        }
        if (data.equals("orders")) {
            list(user);
            return;
        }
        String[] parts = data.split(":", 3);
        if (parts.length < 2) {
            throw stale();
        }
        if (parts[0].equals("pick")) {
            PrintOrder order = orders.byNumber(Long.parseLong(parts[1]));
            PrintOrder updated = service.configure(user, order.id(), "color", part(parts, 2));
            intake.select(user, order.id(), "STL");
            FilamentColor color = colors.available(updated.colorId());
            messages.buttons(user, "Выбран цвет: " + color.label() + ".", List.of(
                    button("Продолжить", "step:" + order.id() + ":profile"),
                    button("Выбрать другой цвет", "colors:" + order.id() + ":0"), button("В меню", "nav:home")));
            return;
        }
        UUID id = UUID.fromString(parts[1]);
        if (parts[0].equals("newConfirm")) {
            BotDialog pending = dialogs.get(user, user);
            if (pending == null || !pending.kind().equals("NEW_CONFIRM") || !id.equals(pending.orderId())) throw stale();
            service.owned(user, id).status().require(OrderStatus.DRAFT, OrderStatus.MODELING);
            service.discardDraft(user, id);
            newOrderMenu(user);
            return;
        }
        if (parts[0].equals("accept")) {
            service.accept(user, id);
            dialogs.clear(user, user);
            return;
        }
        PrintOrder order = service.owned(user, id);
        switch (parts[0]) {
            case "show" -> show(user, id);
            case "cancel" -> messages.buttons(user, "Отменить заказ №" + order.number() + "?", List.of(
                    button("Да, отменить", "cancelYes:" + id), button("Оставить заказ", "show:" + id)));
            case "cancelYes" -> {
                service.cancel(user, id);
                dialogs.clear(user, user);
                intake.clear(user);
            }
            case "submit" -> {
                service.submit(user, id);
                dialogs.clear(user, user);
                intake.clear(user);
            }
            case "colors" -> colors(user, order, Integer.parseInt(part(parts, 2)));
            case "edit" -> colors(user, order, 0);
            case "step" -> {
                order.status().require(OrderStatus.DRAFT);
                dialogs.clear(user, user);
                switch (part(parts, 2)) {
                    case "profile" -> profile(user, order);
                    case "infill" -> infill(user, order);
                    case "quantity" -> quantity(user, order);
                    default -> throw stale();
                }
            }
            case "profile" -> infill(user, service.configure(user, id, "profile", part(parts, 2)));
            case "infill" -> quantity(user, service.configure(user, id, "infill", part(parts, 2)));
            case "quantity", "promo" -> draft(user, service.configure(user, id, parts[0], part(parts, 2)));
            case "input" -> beginInput(user, order, part(parts, 2));
            case "lowkeep" -> {
                BotDialog dialog = dialogs.get(user, user);
                if (dialog == null || !dialog.kind().equals("LOW_INFILL") || !id.equals(dialog.orderId())) {
                    throw stale();
                }
                PrintOrder updated = service.configure(user, id, "infill", dialog.payload());
                dialogs.clear(user, user);
                quantity(user, updated);
            }
            case "photos" -> {
                order.status().require(OrderStatus.MODELING);
                dialogs.clear(user, user);
                intake.select(user, id, "PHOTO");
                messages.buttons(user, "Пришли дополнительные фото по заказу №" + order.number() + ".", List.of(button("Мои заказы", "nav:orders"), button("В меню", "nav:home")));
            }
            case "promoapply" -> applyPromo(user, order);
            case "question", "describe" -> {
                intake.clear(user);
                dialogs.save(user, user, "CHAT", null, id, null);
                messages.buttons(user, "Напиши текстовое сообщение по заказу №" + order.number() + ". Команда PrintLab получит его и ответит тебе здесь.",
                        List.of(button("Назад к заказу", "show:" + id), button("В меню", "nav:home")));
            }
            case "ship" -> {
                order.status().require(OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
                String method = part(parts, 2);
                if (!List.of("COURIER", "PICKUP").contains(method)) {
                    throw stale();
                }
                dialogs.save(user, user, "SHIPPING", "name", id, json.write(Map.of("method", method)));
                shippingPrompt(user, id, "Напиши полные ФИО получателя.");
            }
            case "shipping" -> {
                order.status().require(OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
                messages.buttons(user, "Как отправить заказ СДЭК?", List.of(
                        button("Курьером до двери", "ship:" + id + ":COURIER"),
                        button("В пункт выдачи СДЭК", "ship:" + id + ":PICKUP"), button("В меню", "nav:home")));
            }
            default -> throw stale();
        }
    }

    private void beginInput(long user, PrintOrder order, String field) {
        order.status().require(OrderStatus.DRAFT);
        String text;
        switch (field) {
            case "layer" -> text = "Введи высоту слоя от 0,08 до 0,28 мм. Например, 0,16.";
            case "infill" -> text = "Введи заполнение целым числом от 1 до 100%. Значения ниже 5% обычно не рекомендуем.";
            case "quantity" -> text = "Введи количество деталей целым числом от 1 до 100.";
            case "promo" -> text = "Введи промокод. Затем нажми «Применить промокод», чтобы проверить его условия.";
            default -> throw stale();
        }
        dialogs.save(user, user, "PARAM", field, order.id(), null);
        List<Map<String, String>> buttons = new ArrayList<>();
        if (field.equals("promo")) {
            buttons.add(button("Убрать промокод", "promo:" + order.id() + ":-"));
        }
        buttons.add(button("Назад к заказу", "show:" + order.id()));
        buttons.add(button("В меню", "nav:home"));
        messages.buttons(user, text, buttons);
    }

    public boolean waitingForChoice(long user) {
        BotDialog dialog = dialogs.get(user, user);
        return dialog != null && List.of("NEW_CONFIRM", "NEW_CHOICE", "LOW_INFILL", "PROMO_CONFIRM").contains(dialog.kind());
    }

    public boolean input(long user, String text, long replyTo) {
        BotDialog dialog = dialogs.get(user, user);
        UUID replyOrder = dialogs.replyOrder(user, replyTo);
        if (replyOrder != null && dialog == null) {
            modeling.question(user, replyOrder, text);
            return true;
        }
        if (dialog == null) {
            return false;
        }
        if (dialog.kind().equals("CHAT")) {
            modeling.question(user, dialog.orderId(), text);
            return true;
        }
        if (dialog.kind().equals("SHIPPING")) {
            shippingInput(user, dialog, text);
            return true;
        }
        if (!dialog.kind().equals("PARAM")) {
            return false;
        }
        if (dialog.step().equals("promo")) {
            String code = text.trim().toUpperCase(java.util.Locale.ROOT);
            dialogs.save(user, user, "PROMO_CONFIRM", null, dialog.orderId(), code);
            messages.buttons(user, "Промокод: " + code + "\n\nНажми «Применить промокод», чтобы проверить условия.", List.of(
                    button("Применить промокод", "promoapply:" + dialog.orderId()),
                    button("Ввести другой", "input:" + dialog.orderId() + ":promo"), button("Продолжить без промокода", "promo:" + dialog.orderId() + ":-")));
            return true;
        }
        if (dialog.step().equals("infill")) {
            int value;
            try {
                value = Integer.parseInt(text);
            } catch (NumberFormatException exception) {
                throw new BusinessException("Не получилось распознать число. Попробуй ввести его ещё раз.");
            }
            if (value >= 1 && value < 5) {
                service.owned(user, dialog.orderId()).status().require(OrderStatus.DRAFT);
                dialogs.save(user, user, "LOW_INFILL", null, dialog.orderId(), Integer.toString(value));
                messages.buttons(user, "Заполнение ниже 5% обычно не рекомендуем: внутри будет мало опоры для верхних слоёв.\n\nОставить " + value + "%?",
                        List.of(button("Оставить " + value + "%", "lowkeep:" + dialog.orderId()),
                                button("Изменить заполнение", "input:" + dialog.orderId() + ":infill"), button("В меню", "nav:home")));
                return true;
            }
        }
        PrintOrder order = service.configure(user, dialog.orderId(), dialog.step(), text);
        dialogs.clear(user, user);
        switch (dialog.step()) {
            case "layer" -> infill(user, order);
            case "infill" -> quantity(user, order);
            case "promo" -> {
                if (!order.promoCode().isBlank()) {
                    messages.text(user, "Промокод " + order.promoCode() + " добавлен. Его доступность проверим при расчёте.");
                }
                draft(user, order);
            }
            default -> draft(user, order);
        }
        return true;
    }

    private void applyPromo(long user, PrintOrder order) {
        BotDialog pending = dialogs.get(user, user);
        if (pending == null || !pending.kind().equals("PROMO_CONFIRM") || !order.id().equals(pending.orderId())) throw stale();
        var result = service.applyPromotion(user, order.id(), pending.payload());
        if (result.applied()) {
            messages.text(user, "Промокод принят. " + result.note());
            draft(user, orders.get(order.id()));
        } else {
            dialogs.save(user, user, "PARAM", "promo", order.id(), null);
            messages.buttons(user, result.note() + "\n\nВведи новый промокод или продолжи без него.", List.of(
                    button("Ввести другой промокод", "input:" + order.id() + ":promo"),
                    button("Продолжить без промокода", "promo:" + order.id() + ":-"), button("Назад к заказу", "show:" + order.id())));
        }
    }

    private void shippingInput(long user, BotDialog dialog, String text) {
        service.owned(user, dialog.orderId()).status().require(OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
        JsonNode source = json.read(dialog.payload(), JsonNode.class);
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("method", "name", "phone")) {
            if (source.has(key)) {
                values.put(key, source.path(key).asText());
            }
        }
        if (dialog.step().equals("name")) {
            if (text.length() < 3 || text.length() > 200 || text.trim().split("\\s+").length < 2) {
                throw new BusinessException("Напиши полные ФИО получателя, от 3 до 200 символов.");
            }
            values.put("name", text);
            dialogs.save(user, user, "SHIPPING", "phone", dialog.orderId(), json.write(values));
            shippingPrompt(user, dialog.orderId(), "Напиши телефон получателя с кодом страны, например +79991234567.");
        } else if (dialog.step().equals("phone")) {
            String phone = text.replaceAll("[\\s()\\-]", "");
            if (!phone.matches("\\+[1-9][0-9]{9,14}")) {
                throw new BusinessException("Напиши телефон с кодом страны, например +79991234567.");
            }
            values.put("phone", phone);
            dialogs.save(user, user, "SHIPPING", "address", dialog.orderId(), json.write(values));
            String prompt = "Укажи город и адрес пункта выдачи СДЭК.";
            if (values.get("method").equals("COURIER")) {
                prompt = "Укажи город и полный адрес доставки с индексом.";
            }
            shippingPrompt(user, dialog.orderId(), prompt);
        } else if (dialog.step().equals("address")) {
            if (text.length() < 5 || text.length() > 1000) {
                throw new BusinessException("Укажи город и полный адрес, от 5 до 1000 символов.");
            }
            service.delivery(user, dialog.orderId(), values.get("method"), values.get("name"), values.get("phone"), text);
            dialogs.clear(user, user);
        } else {
            throw stale();
        }
    }

    private void shippingPrompt(long user, UUID id, String text) {
        messages.buttons(user, text, List.of(button("Изменить способ доставки", "shipping:" + id),
                button("Назад к заказу", "show:" + id), button("В меню", "nav:home")));
    }

    private boolean canCancel(PrintOrder order) {
        return switch (order.status()) {
            case DRAFT, MODELING, ESTIMATING, REVIEW_REQUIRED, QUOTE_READY, AWAITING_PAYMENT, EXPIRED -> true;
            default -> false;
        };
    }

    private String part(String[] parts, int index) {
        if (parts.length <= index) {
            throw stale();
        }
        return parts[index];
    }

    private BusinessException stale() {
        return new BusinessException("Эта кнопка уже неактуальна. Открой заказ заново.");
    }
}
