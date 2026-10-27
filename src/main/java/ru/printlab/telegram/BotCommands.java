package ru.printlab.telegram;

import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Money;
import ru.printlab.order.*;
import ru.printlab.pricing.Estimate;
import ru.printlab.storage.StlValidator;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

@Component
public class BotCommands {
    private static final Set<String> ADMIN_COMMANDS = Set.of("/admin", "/slice", "/approve", "/paid", "/status",
            "/refund", "/actual", "/report", "/retry_notifications", "/reslice", "/model_reply", "/model_stl");
    private final OrderService service;
    private final OrderDao orders;
    private final AccountingService accounting;
    private final Notifications messages;
    private final OutboxDao outbox;
    private final TelegramGateway telegram;
    private final ModelingService modeling;
    private final CustomerFlow flow;
    private final OperatorPanel panel;
    private final DialogDao dialogs;

    public BotCommands(OrderService service, OrderDao orders, AccountingService accounting, Notifications messages,
                       OutboxDao outbox, TelegramGateway telegram, ModelingService modeling, CustomerFlow flow,
                       OperatorPanel panel, DialogDao dialogs) {
        this.service = service;
        this.orders = orders;
        this.accounting = accounting;
        this.messages = messages;
        this.outbox = outbox;
        this.telegram = telegram;
        this.modeling = modeling;
        this.flow = flow;
        this.panel = panel;
        this.dialogs = dialogs;
    }

    public void handle(long user, JsonNode message, String callback) {
        try {
            if (callback != null) {
                if (callback.startsWith("op:") || callback.startsWith("cat:")) {
                    panel.callback(user, user, callback);
                } else {
                    flow.callback(user, callback);
                }
                return;
            }
            String text = message.path("text").asText("").trim();
            if (text.length() > 2000) {
                throw new BusinessException("Сообщение слишком длинное. Раздели его на части до 2000 символов.");
            }
            if (text.equals(BotText.get("button.print"))) { flow.print(user); return; }
            if (text.equals(BotText.get("button.model"))) { flow.model(user); return; }
            if (text.equals(BotText.get("button.orders"))) { flow.list(user); return; }
            if (text.equals(BotText.get("button.contact"))) { flow.contact(user); return; }
            String[] args = text.split("\\s+");
            String command = args[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
            if (ADMIN_COMMANDS.contains(command)) {
                dialogs.clear(user, user);
                admin(user, user, command, args, text);
                return;
            }
            if (text.startsWith("/")) {
                dialogs.clear(user, user);
                switch (command) {
                    case "/start", "/terms" -> flow.home(user);
                    case "/help" -> flow.contact(user);
                    case "/model" -> flow.model(user);
                    case "/id" -> messages.text(user, "Telegram ID: " + user);
                    case "/new" -> flow.print(user);
                    case "/draft" -> flow.draft(user, service.newDraft(user));
                    case "/orders" -> flow.list(user);
                    case "/order" -> flow.show(user, orders.byNumber(number(args, 1)).id());
                    case "/cancel" -> service.cancel(user, orders.byNumber(number(args, 1)).id());
                    case "/quantity" -> flow.draft(user, service.configure(user, service.newDraft(user).id(), "quantity", argument(args, 1)));
                    case "/promo" -> flow.draft(user, service.configure(user, service.newDraft(user).id(), "promo", argument(args, 1)));
                    default -> flow.home(user);
                }
                return;
            }
            if (message.has("document") && message.path("caption").asText("").trim().startsWith("/model_stl")) {
                document(user, user, message.path("document"), message.path("caption").asText(""));
                return;
            }
            if (service.isAdmin(user) && panel.input(user, user, message)) {
                return;
            }
            if (message.has("document")) {
                document(user, user, message.path("document"), message.path("caption").asText(""));
                return;
            }
            if (message.path("photo").isArray() && !message.path("photo").isEmpty()) {
                JsonNode largest = message.path("photo").get(0);
                for (JsonNode photo : message.path("photo")) {
                    if (photo.path("width").asLong() * photo.path("height").asLong()
                            > largest.path("width").asLong() * largest.path("height").asLong()) {
                        largest = photo;
                    }
                }
                modeling.photo(user, largest.path("file_id").asText(""), false, message.path("caption").asText(""));
                return;
            }
            if (!text.isBlank() && flow.input(user, text, message.path("reply_to_message").path("message_id").asLong())) {
                return;
            }
            if (!modeling.describe(user, text)) {
                flow.home(user);
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Эта кнопка уже неактуальна. Открой заказ заново.");
        }
    }

    public void handleGroup(long user, long chat, JsonNode message, String callback) {
        service.requireAdmin(user);
        try {
            if (callback != null) {
                panel.callback(user, chat, callback);
                return;
            }
            String caption = message.path("caption").asText("");
            if (message.has("document") && caption.trim().startsWith("/model_stl")) {
                document(user, chat, message.path("document"), caption);
                return;
            }
            String text = message.path("text").asText("").trim();
            if (text.length() > 2000) {
                throw new BusinessException("Сообщение должно быть не длиннее 2000 символов.");
            }
            String[] args = text.split("\\s+");
            String command = args[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
            if (ADMIN_COMMANDS.contains(command)) {
                dialogs.clear(user, chat);
                admin(user, chat, command, args, text);
            } else if (command.equals("/orders")) {
                panel.list(user, chat);
            } else if (command.equals("/order")) {
                panel.show(user, chat, orders.byNumber(number(args, 1)).id());
            } else if (!text.startsWith("/")) {
                panel.input(user, chat, message);
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Проверь формат числа или открой заказ заново через админ-меню.");
        }
    }

    private void document(long user, long responseChat, JsonNode file, String caption) {
        String name = file.path("file_name").asText("");
        String lowerName = name.toLowerCase(Locale.ROOT);
        boolean modeled = caption.trim().startsWith("/model_stl");
        if (modeled) {
            service.requireAdmin(user);
        }
        if (file.path("file_size").asLong() > StlValidator.MAX_BYTES) {
            throw new BusinessException("Файл слишком большой. Максимальный размер 20 МБ.");
        }
        if (!modeled && (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png"))) {
            modeling.photo(user, file.path("file_id").asText(""), true, caption);
            return;
        }
        if (!lowerName.endsWith(".stl")) {
            throw new BusinessException("Для печати нужен STL-файл. Для моделирования можно прислать фото или эскиз.");
        }
        if (!modeled) {
            service.newDraft(user);
        }
        String fileId = file.path("file_id").asText();
        byte[] bytes;
        try {
            bytes = telegram.download(fileId);
        } catch (TelegramFailure failure) {
            if (failure.permanent()) {
                throw new BusinessException("Не получилось загрузить файл из Telegram. Пришли его ещё раз или напиши нам.");
            }
            throw failure;
        }
        if (modeled) {
            String[] args = caption.trim().split("\\s+");
            if (args.length != 2 || !args[0].split("@", 2)[0].equals("/model_stl")) {
                throw new BusinessException("Подпись готового STL: /model_stl НОМЕР.");
            }
            modeling.attach(user, number(args, 1), fileId, name, bytes);
            dialogs.clear(user, responseChat);
            messages.text(responseChat, "Модель передана клиенту.");
            return;
        }
        PrintOrder order = service.attach(user, fileId, name, bytes);
        dialogs.clear(user, user);
        flow.colors(user, order, 0);
    }

    private void admin(long user, long responseChat, String command, String[] args, String fullText) {
        service.requireAdmin(user);
        switch (command) {
            case "/admin" -> panel.menu(user, responseChat);
            case "/slice" -> service.estimate(user, number(args, 1), estimate(args));
            case "/approve" -> {
                String[] parts = fullText.split("\\s+", 3);
                service.approve(user, number(args, 1), argument(parts, 2));
                messages.text(responseChat, "Финансовое согласование сохранено.");
            }
            case "/paid" -> {
                service.paid(user, number(args, 1), decimal(args, 2), argument(args, 3));
                messages.text(responseChat, "Оплата учтена.");
            }
            case "/status" -> service.production(user, number(args, 1), OrderStatus.valueOf(argument(args, 2).toUpperCase(Locale.ROOT)));
            case "/refund" -> service.refund(user, number(args, 1), argument(args, 2));
            case "/actual" -> {
                Estimate estimate = new Estimate(decimal(args, 2), decimal(args, 6), decimal(args, 3),
                        decimal(args, 4), decimal(args, 5), decimal(args, 7), BigDecimal.ZERO);
                ActualCosts actual = accounting.actual(user, number(args, 1), estimate, decimal(args, 8));
                messages.text(responseChat, "Факт сохранён. Затраты по ставкам заказа: " + Money.format(actual.totalCost())
                        + ". Маржинальный доход: " + Money.format(actual.contribution()));
            }
            case "/report" -> messages.text(responseChat, accounting.report(user));
            case "/retry_notifications" -> {
                outbox.retryFailed();
                messages.text(responseChat, "Ошибочные уведомления возвращены в очередь.");
            }
            case "/reslice" -> service.reslice(user, number(args, 1));
            case "/model_reply" -> {
                String[] parts = fullText.split("\\s+", 3);
                modeling.reply(user, number(args, 1), argument(parts, 2));
                messages.text(responseChat, "Сообщение отправлено клиенту.");
            }
            case "/model_stl" -> throw new BusinessException("Отправьте готовый STL как файл с подписью /model_stl НОМЕР.");
            default -> throw new BusinessException("Неизвестная команда.");
        }
    }

    private Estimate estimate(String[] args) {
        if (args.length == 4 || args.length == 5) {
            int plates = 1;
            if (args.length == 5) {
                plates = Math.toIntExact(number(args, 4));
            }
            return new Estimate(decimal(args, 2), BigDecimal.ZERO, decimal(args, 3), BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, plates);
        }
        if (args.length != 9 && args.length != 10) {
            throw new BusinessException("Ручной расчёт: /slice НОМЕР граммы минутыПечати [пластин]. Полный формат: /admin.");
        }
        int plates = 1;
        if (args.length > 9) {
            plates = Math.toIntExact(number(args, 9));
        }
        return new Estimate(decimal(args, 2), decimal(args, 6), decimal(args, 3), decimal(args, 4),
                decimal(args, 5), decimal(args, 7), decimal(args, 8), plates);
    }

    private String argument(String[] args, int index) {
        if (index >= args.length) {
            throw new BusinessException("Не хватает аргументов. Подсказка: /help или /admin.");
        }
        return args[index];
    }

    private long number(String[] args, int index) {
        return Long.parseLong(argument(args, index));
    }

    private BigDecimal decimal(String[] args, int index) {
        String value = argument(args, index).replace(',', '.');
        if (!value.matches("[0-9]{1,8}(\\.[0-9]{1,4})?")) {
            throw new BusinessException("Нужно неотрицательное число, до четырёх знаков после запятой.");
        }
        return new BigDecimal(value);
    }
}
