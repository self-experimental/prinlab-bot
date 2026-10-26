package ru.printlab.telegram;

import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Money;
import ru.printlab.config.AppProperties;
import ru.printlab.config.Material;
import ru.printlab.order.*;
import ru.printlab.pricing.Estimate;
import ru.printlab.storage.StlValidator;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class BotCommands {
    private static final Set<String> ADMIN_COMMANDS = Set.of("/admin", "/slice", "/approve", "/paid", "/status",
            "/refund", "/actual", "/report", "/retry_notifications", "/reslice", "/model_reply");
    private final OrderService service;
    private final OrderDao orders;
    private final AccountingService accounting;
    private final Notifications messages;
    private final OutboxDao outbox;
    private final TelegramGateway telegram;
    private final AppProperties properties;
    private final Clock clock;
    private final ModelingService modeling;

    public BotCommands(OrderService service, OrderDao orders, AccountingService accounting, Notifications messages,
                       OutboxDao outbox, TelegramGateway telegram, AppProperties properties, Clock clock,
                       ModelingService modeling) {
        this.service = service;
        this.orders = orders;
        this.accounting = accounting;
        this.messages = messages;
        this.outbox = outbox;
        this.telegram = telegram;
        this.properties = properties;
        this.clock = clock;
        this.modeling = modeling;
    }

    public void handle(long user, JsonNode message, String callback) {
        try {
            if (callback != null) {
                callback(user, callback);
                return;
            }
            if (message.has("document")) {
                document(user, message.path("document"), message.path("caption").asText(""));
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
            String text = message.path("text").asText("").trim();
            if (text.length() > 2000) {
                throw new BusinessException("Сообщение слишком длинное. Используйте /help.");
            }
            String[] args = text.split("\\s+");
            String command = args[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
            if (ADMIN_COMMANDS.contains(command)) {
                admin(user, command, args, text);
                return;
            }
            switch (command) {
                case "/start", "/terms" -> welcome(user);
                case "/help" -> messages.text(user, "/new — печать по STL\n/model — моделирование по фото (вручную)\n/draft — продолжить черновик\n/orders — мои заказы\n/order НОМЕР — детали\n/quantity 3 — количество в черновике\n/promo FIRST50 — промокод\n/promo - — убрать промокод\n/cancel НОМЕР — отмена до оплаты\n/terms — условия\n/id — мои идентификаторы\nПоддержка: " + properties.contact());
                case "/model" -> {
                    PrintOrder request = modeling.request(user);
                    messages.text(user, "Заявка на моделирование №" + request.number()
                            + ". Пришлите фото или JPG/PNG как файл. В подписи укажите размеры и назначение детали."
                            + " Оператор обработает заявку вручную и согласует стоимость.");
                }
                case "/id" -> messages.text(user, "User UUID: " + orders.customerId(user) + "\nTelegram ID: " + user);
                case "/new", "/draft" -> draft(user, service.newDraft(user));
                case "/orders" -> list(user);
                case "/order" -> show(user, number(args, 1));
                case "/cancel" -> service.cancel(user, orders.byNumber(number(args, 1)).id());
                case "/quantity" -> draft(user, service.configure(user, service.newDraft(user).id(), "quantity", argument(args, 1)));
                case "/promo" -> draft(user, service.configure(user, service.newDraft(user).id(), "promo", argument(args, 1)));
                default -> {
                    if (text.startsWith("/") || !modeling.describe(user, text)) {
                        messages.text(user, "Печать по STL: /new. Моделирование по фото: /model. Список команд: /help.");
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Неверный формат команды или числа. Подсказка: /help, для оператора /admin.");
        }
    }

    private void welcome(long user) {
        String text = "PrintLab — печать по вашей 3D-модели.\nЗагрузите STL, выберите материал и параметры."
                + "\nНет модели? Пришлите фото — мы обработаем заявку на моделирование вручную. Команда: /model."
                + "\nПринтер Bambu Lab A1 mini: до 180×180×180 мм, один материал и один цвет на заказ. Многоцветная печать пока недоступна."
                + "\nМодели принимаются в миллиметрах; размер проверяется при слайсинге."
                + "\nПеред оплатой согласуем цену и срок. Платёж подтверждает оператор."
                + "\nФото, модели и описание заявки передаются операторам для обработки заказа."
                + "\nКонтакт: " + properties.contact();
        if (!properties.termsUrl().isBlank()) {
            text += "\nУсловия заказа: " + properties.termsUrl();
        }
        if (!properties.privacyUrl().isBlank()) {
            text += "\nОбработка данных: " + properties.privacyUrl();
        }
        text += "\nЗапуск: " + Money.format(properties.rates().launchFee());
        if (properties.rates().launchFeePerOrder()) {
            text += " на заказ.";
        } else {
            text += " за каждую пластину (отдельный запуск).";
        }
        if (properties.promotion().enabled()) {
            text += "\n" + properties.promotion().code() + ": " + properties.promotion().rate().multiply(new BigDecimal("100"))
                    + "% на печать первого оплаченного заказа, до " + Money.format(properties.promotion().maxDiscount())
                    + "; не более " + properties.promotion().maxPaidOrders() + " оплат, бюджет "
                + Money.format(properties.promotion().budget()) + ". До " + properties.promotion().endsAt()
                    + ". Доставка без скидки. Место временно резервируется при расчёте; отдельные заказы требуют согласования.";
            if (!properties.rates().discountLaunchFee()) {
                text += " Плата за запуск в скидку не входит.";
            }
        }
        messages.buttons(user, text, List.of(Notifications.button("Принимаю условия", "agree"),
                Notifications.button("Мои заказы", "orders")));
    }

    private void document(long user, JsonNode file, String caption) {
        String name = file.path("file_name").asText("");
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png")) {
            if (file.path("file_size").asLong() > StlValidator.MAX_BYTES) {
                throw new BusinessException("Изображение должно быть до 20 МиБ.");
            }
            modeling.photo(user, file.path("file_id").asText(""), true, caption);
            return;
        }
        boolean modeled = caption.trim().startsWith("/model_stl");
        if (modeled) {
            service.requireAdmin(user);
        } else {
            service.newDraft(user);
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".stl") || file.path("file_size").asLong() > StlValidator.MAX_BYTES) {
            throw new BusinessException("Пришлите STL для печати или фото/JPG/PNG для моделирования, до 20 МиБ.");
        }
        String fileId = file.path("file_id").asText();
        byte[] bytes;
        try {
            bytes = telegram.download(fileId);
        } catch (TelegramFailure failure) {
            if (failure.permanent()) {
                throw new BusinessException("Telegram не отдал файл. Пришлите STL повторно или свяжитесь с оператором.");
            }
            throw failure;
        }
        if (modeled) {
            String[] args = caption.trim().split("\\s+");
            if (args.length != 2 || !args[0].equals("/model_stl")) {
                throw new BusinessException("Подпись готового STL: /model_stl НОМЕР.");
            }
            PrintOrder order = modeling.attach(user, number(args, 1), fileId, name, bytes);
            messages.text(user, "Модель передана клиенту в заказ №" + order.number() + ".");
            return;
        }
        PrintOrder order = service.attach(user, fileId, name, bytes);
        materialMenu(user, order);
    }

    private void callback(long user, String data) {
        if (data.equals("agree")) {
            orders.consent(user, clock.instant());
            draft(user, service.newDraft(user));
            return;
        }
        if (data.equals("orders")) {
            list(user);
            return;
        }
        String[] parts = data.split(":", 3);
        if (parts.length < 2) {
            throw new BusinessException("Кнопка устарела. Откройте /draft.");
        }
        UUID id = UUID.fromString(parts[1]);
        switch (parts[0]) {
            case "accept" -> service.accept(user, id);
            case "cancel" -> service.cancel(user, id);
            case "submit" -> service.submit(user, id);
            case "material" -> {
                service.configure(user, id, "material", argument(parts, 2));
                profileMenu(user, orders.get(id));
            }
            case "profile" -> {
                service.configure(user, id, "profile", argument(parts, 2));
                infillMenu(user, orders.get(id));
            }
            case "infill" -> {
                service.configure(user, id, "infill", argument(parts, 2));
                quantityMenu(user, orders.get(id));
            }
            case "quantity", "promo" -> draft(user, service.configure(user, id, parts[0], argument(parts, 2)));
            case "edit" -> {
                PrintOrder order = service.owned(user, id);
                order.status().require(OrderStatus.DRAFT);
                materialMenu(user, order);
            }
            default -> throw new BusinessException("Кнопка устарела. Откройте /draft.");
        }
    }

    private void draft(long user, PrintOrder order) {
        if (order.modelId() == null) {
            messages.text(user, "Черновик №" + order.number() + ". Пришлите STL как файл (до 20 МиБ). Размеры модели должны быть в миллиметрах.");
            return;
        }
        if (order.materialId() == null) {
            materialMenu(user, order);
            return;
        }
        List<Map<String, String>> buttons = new ArrayList<>();
        buttons.add(Notifications.button("Рассчитать стоимость", "submit:" + order.id()));
        buttons.add(Notifications.button("Изменить параметры", "edit:" + order.id()));
        if (properties.promotion().enabled() && order.promoCode().isBlank()) {
            buttons.add(Notifications.button("Применить " + properties.promotion().code(), "promo:" + order.id() + ":" + properties.promotion().code()));
        }
        buttons.add(Notifications.button("Отменить", "cancel:" + order.id()));
        messages.buttons(user, "Черновик №" + order.number() + "\nФайл: " + orders.model(order.modelId()).name()
                + "\n" + service.summary(order) + "\nДоставка: самовывоз (0 ₽); иной вариант согласуется до оплаты."
                + "\nИзменить количество: /quantity 3. Другой промокод: /promo КОД.", buttons);
    }

    private void materialMenu(long user, PrintOrder order) {
        List<Map<String, String>> buttons = new ArrayList<>();
        for (Map.Entry<String, Material> entry : properties.materials().entrySet()) {
            if (entry.getValue().available()) {
                buttons.add(Notifications.button(entry.getValue().label(), "material:" + order.id() + ":" + entry.getKey()));
            }
        }
        messages.buttons(user, "Файл получен. Выберите один материал и один цвет для всего заказа:", buttons);
    }

    private void profileMenu(long user, PrintOrder order) {
        messages.buttons(user, "Выберите высоту слоя:", List.of(
                Notifications.button("Стандарт · 0,20 мм", "profile:" + order.id() + ":STANDARD"),
                Notifications.button("Детально · 0,12 мм", "profile:" + order.id() + ":FINE")));
    }

    private void infillMenu(long user, PrintOrder order) {
        messages.buttons(user, "Выберите заполнение:", List.of(
                Notifications.button("15%", "infill:" + order.id() + ":15"),
                Notifications.button("30%", "infill:" + order.id() + ":30"),
                Notifications.button("60%", "infill:" + order.id() + ":60")));
    }

    private void quantityMenu(long user, PrintOrder order) {
        messages.buttons(user, "Количество изделий? Для другого количества: /quantity 3.", List.of(
                Notifications.button("1", "quantity:" + order.id() + ":1"),
                Notifications.button("2", "quantity:" + order.id() + ":2"),
                Notifications.button("5", "quantity:" + order.id() + ":5")));
    }

    private void list(long user) {
        StringBuilder text = new StringBuilder("Последние заказы:\n");
        for (PrintOrder order : orders.list(user, service.isAdmin(user))) {
            text.append("№").append(order.number()).append(" · ").append(order.status().label())
                    .append(" · /order ").append(order.number()).append('\n');
        }
        messages.text(user, text.toString());
    }

    private void show(long user, long number) {
        PrintOrder order = orders.byNumber(number);
        if (!service.isAdmin(user)) {
            service.owned(user, order.id());
        }
        messages.text(user, "Заказ №" + number + " · " + order.status().label() + "\n" + service.summary(order));
        if (service.isAdmin(user)) {
            modeling.show(user, order);
        }
        if (order.quoteId() != null) {
            Quote quote = orders.quote(order.quoteId());
            if (service.isAdmin(user)) {
                messages.text(user, service.internalSummary(order, quote));
            } else if (order.status() == OrderStatus.QUOTE_READY) {
                messages.quote(order, quote);
            } else if (order.paidAt() != null || order.status() == OrderStatus.AWAITING_PAYMENT) {
                messages.text(user, "Согласованная сумма: " + Money.format(quote.snapshot().publicPrice().total()));
            }
        }
    }

    private void admin(long user, String command, String[] args, String fullText) {
        service.requireAdmin(user);
        switch (command) {
            case "/admin" -> messages.text(user, "Команды оператора\n/orders · /order НОМЕР\n"
                    + "/slice НОМЕР граммы минутыПечати [пластин] — ручной расчёт без доставки и доп. отходов\n"
                    + "/slice НОМЕР граммы минутыПечати подготовкаМин обработкаМин отходыГ себестДоставки ценаДоставки [пластин]\n"
                    + "/approve НОМЕР причина — принять доход ниже порога\n"
                    + "/paid НОМЕР сумма идентификаторПеревода — только после поступления денег\n"
                    + "/status НОМЕР PRINTING|QUALITY_CHECK|READY|COMPLETED\n"
                    + "/refund НОМЕР идентификаторВозврата — после полного возврата денег\n"
                    + "/actual НОМЕР граммы минутыПечати подготовкаМин обработкаМин отходыГ себестДоставки допРасходы\n"
                    + "/reslice НОМЕР — повторный автоматический расчёт\n"
                    + "/model_reply НОМЕР текст — сообщение по заявке на моделирование\n"
                    + "/operator_chat — отправьте в общей группе для подключения уведомлений\n"
                    + "Готовый STL: отправьте файл с подписью /model_stl НОМЕР\n"
                    + "/report — экономика\n/retry_notifications — повторить неотправленные уведомления\n"
                    + "Неотправленных уведомлений с ошибкой: " + outbox.failedCount()
                    + ". Все величины — на весь тираж. В /slice последним аргументом можно указать число пластин; по умолчанию одна.");
            case "/slice" -> service.estimate(user, number(args, 1), estimate(args));
            case "/approve" -> {
                String[] parts = fullText.split("\\s+", 3);
                service.approve(user, number(args, 1), argument(parts, 2));
                messages.text(user, "Финансовое согласование сохранено.");
            }
            case "/paid" -> {
                service.paid(user, number(args, 1), decimal(args, 2), argument(args, 3));
                messages.text(user, "Оплата учтена.");
            }
            case "/status" -> service.production(user, number(args, 1), OrderStatus.valueOf(argument(args, 2).toUpperCase(Locale.ROOT)));
            case "/refund" -> service.refund(user, number(args, 1), argument(args, 2));
            case "/actual" -> {
                Estimate estimate = new Estimate(decimal(args, 2), decimal(args, 6), decimal(args, 3),
                        decimal(args, 4), decimal(args, 5), decimal(args, 7), BigDecimal.ZERO);
                ActualCosts actual = accounting.actual(user, number(args, 1), estimate, decimal(args, 8));
                messages.text(user, "Факт сохранён. Затраты по ставкам заказа: " + Money.format(actual.totalCost())
                        + ". Маржинальный доход: " + Money.format(actual.contribution()));
            }
            case "/report" -> messages.text(user, accounting.report(user));
            case "/retry_notifications" -> {
                outbox.retryFailed();
                messages.text(user, "Ошибочные уведомления возвращены в очередь.");
            }
            case "/reslice" -> service.reslice(user, number(args, 1));
            case "/model_reply" -> {
                String[] parts = fullText.split("\\s+", 3);
                modeling.reply(user, number(args, 1), argument(parts, 2));
                messages.text(user, "Сообщение передано клиенту.");
            }
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
