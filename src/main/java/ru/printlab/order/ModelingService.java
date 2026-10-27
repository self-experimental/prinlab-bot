package ru.printlab.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.storage.ModelFile;
import ru.printlab.storage.ModelStorage;
import ru.printlab.telegram.Notifications;

import java.time.Clock;
import java.util.List;

@Service
@Transactional(noRollbackFor = BusinessException.class)
public class ModelingService {
    private final OrderService service;
    private final OrderDao orders;
    private final ModelingDao modeling;
    private final ModelStorage storage;
    private final Notifications messages;
    private final Clock clock;

    public ModelingService(OrderService service, OrderDao orders, ModelingDao modeling,
                           ModelStorage storage, Notifications messages, Clock clock) {
        this.service = service;
        this.orders = orders;
        this.modeling = modeling;
        this.storage = storage;
        this.messages = messages;
        this.clock = clock;
    }

    public PrintOrder request(long user) {
        orders.lock();
        if (!orders.consented(user)) {
            throw new BusinessException("Открой главное меню и нажми «Заказать модель по фото», чтобы продолжить.");
        }
        var pending = modeling.pending(orders.customerId(user));
        if (pending != null) {
            return orders.get(pending);
        }
        PrintOrder order = orders.create(user, clock.instant());
        orders.status(order.id(), OrderStatus.MODELING);
        orders.event(order.id(), user, "MODELING_REQUESTED", "Ручное моделирование по фото", clock.instant());
        return orders.get(order.id());
    }

    public void photo(long user, String fileId, boolean document, String caption) {
        if (fileId == null || fileId.isBlank() || caption.length() > 1024) {
            throw new BusinessException("Пришли фото с описанием до 1024 символов.");
        }
        PrintOrder order = request(user);
        modeling.add(order.id(), new ModelingPhoto(fileId, document, caption), clock.instant());
        modeling.postponeReceipt(order.id(), clock.instant().plusSeconds(3));
        messages.referenceToAdmins(order, fileId, document, "3D-моделирование · заказ №" + order.number());
        messages.adminOrder(order, "Фото для заявки №" + order.number() + "\n\nОписание: " + caption);
    }

    public PrintOrder attach(long admin, long number, String fileId, String name, byte[] bytes) {
        service.requireAdmin(admin);
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        order.status().require(OrderStatus.MODELING);
        if (modeling.photos(order.id()).isEmpty()) {
            throw new BusinessException("Сначала добавьте фото в заявку.");
        }
        ModelFile model = storage.save(order.customerId(), fileId, name, bytes);
        orders.model(order.id(), model);
        orders.status(order.id(), OrderStatus.DRAFT);
        orders.event(order.id(), admin, "MODELED_STL_ATTACHED", model.sha256(), clock.instant());
        messages.document(order.customerId(), fileId, "Готовая модель для заказа №" + number);
        messages.buttons(order.customerId(), "Модель по фото готова! Теперь можно выбрать параметры и рассчитать печать.",
                List.of(Notifications.button("Выбрать параметры печати", "edit:" + order.id())));
        return orders.get(order.id());
    }

    public void reply(long admin, long number, String text) {
        service.requireAdmin(admin);
        PrintOrder order = orders.byNumber(number);
        if (text.isBlank() || text.length() > 2000) {
            throw new BusinessException("Сообщение: от 1 до 2000 символов.");
        }
        messages.orderButtons(orders.telegramId(order.customerId()), "Команда PrintLab по заказу №" + number + ":\n\n" + text,
                List.of(Notifications.button("Ответить", "question:" + order.id()),
                        Notifications.button("Мои заказы", "nav:orders")), order.id());
        orders.event(order.id(), admin, "OPERATOR_REPLY", text, clock.instant());
    }

    public boolean describe(long user, String text) {
        orders.lock();
        var pending = modeling.pending(orders.customerId(user));
        if (pending == null || text.isBlank()) {
            return false;
        }
        PrintOrder order = orders.get(pending);
        orders.event(order.id(), user, "MODELING_DESCRIPTION", text, clock.instant());
        messages.adminOrder(order, "Дополнение к заявке на моделирование №" + order.number() + ":\n\n" + text);
        messages.text(user, "Описание добавлено в заявку №" + order.number() + ".");
        return true;
    }

    public void show(long admin, long chat, PrintOrder order) {
        service.requireAdmin(admin);
        for (ModelingPhoto photo : modeling.photos(order.id())) {
            messages.reference(chat, photo.fileId(), photo.document(), "Заявка №" + order.number(), order.id());
            if (!photo.caption().isBlank()) {
                messages.orderText(chat, photo.caption(), order.id());
            }
        }
    }

    public void question(long user, java.util.UUID id, String text) {
        PrintOrder order = service.owned(user, id);
        if (text.isBlank() || text.length() > 2000) {
            throw new BusinessException("Сообщение должно содержать от 1 до 2000 символов.");
        }
        messages.adminOrder(order, "Сообщение клиента по заказу №" + order.number() + ":\n\n" + text);
        orders.event(order.id(), user, "CUSTOMER_MESSAGE", text, clock.instant());
        messages.text(user, "Сообщение получили! Команда PrintLab ответит тебе здесь.");
    }

    public void receipts() {
        orders.lock();
        for (java.util.UUID id : modeling.dueReceipts(clock.instant())) {
            PrintOrder order = orders.get(id);
            modeling.removeReceipt(id);
            if (order.status() == OrderStatus.MODELING) {
                messages.buttons(order.customerId(), "Фото получили! Заявка №" + order.number()
                        + " у нас. Если хочешь что-то добавить, присылай ещё фото или напиши сообщение сюда. Команда PrintLab ответит тебе в этом чате.",
                        List.of(Notifications.button("Добавить описание", "describe:" + id),
                                Notifications.button("Мои заказы", "nav:orders"),
                                Notifications.button("Назад в меню", "nav:home")));
            }
        }
    }
}
