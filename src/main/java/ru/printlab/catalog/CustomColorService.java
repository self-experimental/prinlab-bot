package ru.printlab.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.order.*;
import ru.printlab.telegram.Notifications;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import static ru.printlab.telegram.Notifications.button;

@Service
@Transactional(noRollbackFor = BusinessException.class)
public class CustomColorService {
    private static final Pattern HEX = Pattern.compile("(?i)(?<![a-z0-9])#?([0-9a-f]{6})(?![a-z0-9])");
    private final ColorRequestDao requests;
    private final ColorService colors;
    private final OrderService service;
    private final OrderDao orders;
    private final Notifications messages;
    private final Clock clock;

    public CustomColorService(ColorRequestDao requests, ColorService colors, OrderService service, OrderDao orders, Notifications messages, Clock clock) {
        this.requests = requests;
        this.colors = colors;
        this.service = service;
        this.orders = orders;
        this.messages = messages;
        this.clock = clock;
    }

    public void request(long user, UUID id, String text) {
        orders.lock();
        PrintOrder order = service.owned(user, id);
        order.status().require(OrderStatus.DRAFT);
        if (order.modelId() == null) throw new BusinessException("Сначала пришли STL-модель.");
        if (text.length() < 2 || text.length() > 500) throw new BusinessException("Напиши название или описание цвета, от 2 до 500 символов. HEX-код можно добавить в том же сообщении.");
        String hex = null;
        var match = HEX.matcher(text);
        if (match.find()) hex = "#" + match.group(1).toUpperCase(Locale.ROOT);
        ColorRequest request = requests.create(id, text, hex, clock.instant());
        orders.color(id, null);
        orders.event(id, user, "COLOR_REQUESTED", text, clock.instant());
        messages.text(user, "Предложение цвета получили! Команда PrintLab проверит, сможем ли напечатать его, и ответит здесь.");
        messages.adminButtons(order, "Свой цвет для заказа №" + order.number() + ":\n\n" + text, List.of(
                button("Согласовать цвет", "cc:accept:" + request.id()), button("Отказать", "cc:reject:" + request.id()), button("Открыть заказ", "op:open:" + id)));
    }

    public ColorRequest pending(long admin, UUID requestId) {
        service.requireAdmin(admin);
        ColorRequest request = requests.get(requestId);
        PrintOrder order = orders.get(request.orderId());
        if (order.deleted() || order.status() != OrderStatus.DRAFT || !request.status().equals("PENDING"))
            throw new BusinessException("Предложение цвета уже неактуально. Откройте заказ заново.");
        return request;
    }

    public void approve(long admin, UUID requestId, String name, BigDecimal price, int grams) {
        orders.lock();
        ColorRequest request = pending(admin, requestId);
        // The purchased-on-demand color is restricted to this order until an operator makes it generally available.
        FilamentColor color = colors.save(admin, new FilamentColor(null, name, null, null, request.hexCode(), null, false, false, price, grams));
        requests.resolve(requestId, "APPROVED", color.id(), clock.instant());
        service.configure(orders.telegramId(orders.get(request.orderId()).customerId()), request.orderId(), "color", color.id().toString());
        PrintOrder order = orders.get(request.orderId());
        orders.event(order.id(), admin, "COLOR_APPROVED", color.id().toString(), clock.instant());
        messages.buttons(order.customerId(), "Сможем напечатать цветом «" + color.label() + "».\n\nВозможно увеличение срока выполнения заказа: для этого цвета нужно купить пластик. Точный срок согласуем до оплаты.", List.of(
                button("Продолжить", "step:" + order.id() + ":profile"), button("Выбрать доступный цвет", "colors:" + order.id() + ":0")));
    }

    public void reject(long admin, UUID requestId) {
        orders.lock();
        ColorRequest request = pending(admin, requestId);
        requests.resolve(requestId, "REJECTED", null, clock.instant());
        PrintOrder order = orders.get(request.orderId());
        orders.event(order.id(), admin, "COLOR_REJECTED", request.description(), clock.instant());
        messages.buttons(order.customerId(), "Сейчас не сможем напечатать этим цветом. Выбери другой из доступных.",
                List.of(button("Выбрать доступный цвет", "colors:" + order.id() + ":0")));
    }
}
