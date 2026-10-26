package ru.printlab.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Money;
import ru.printlab.config.AppProperties;
import ru.printlab.config.Material;
import ru.printlab.pricing.Estimate;
import ru.printlab.pricing.PricingService;
import ru.printlab.pricing.PricingSnapshot;
import ru.printlab.promotion.PromotionDao;
import ru.printlab.promotion.PromotionResult;
import ru.printlab.promotion.PromotionService;
import ru.printlab.storage.ModelFile;
import ru.printlab.storage.ModelStorage;
import ru.printlab.telegram.Notifications;
import ru.printlab.slicing.SlicingDao;
import ru.printlab.slicing.SlicerProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(noRollbackFor = BusinessException.class)
public class OrderService {
    private final OrderDao orders;
    private final PaymentDao payments;
    private final PricingService pricing;
    private final PromotionService promotions;
    private final PromotionDao reservations;
    private final AppProperties properties;
    private final ModelStorage storage;
    private final Notifications notifications;
    private final Clock clock;
    private final SlicingDao slicing;
    private final SlicerProperties slicerProperties;

    public OrderService(OrderDao orders, PaymentDao payments, PricingService pricing, PromotionService promotions,
                        PromotionDao reservations, AppProperties properties, ModelStorage storage,
                        Notifications notifications, Clock clock, SlicingDao slicing, SlicerProperties slicerProperties) {
        this.orders = orders;
        this.payments = payments;
        this.pricing = pricing;
        this.promotions = promotions;
        this.reservations = reservations;
        this.properties = properties;
        this.storage = storage;
        this.notifications = notifications;
        this.clock = clock;
        this.slicing = slicing;
        this.slicerProperties = slicerProperties;
    }

    public boolean isAdmin(long user) {
        return properties.adminIds().contains(user);
    }

    public void requireAdmin(long user) {
        if (!isAdmin(user)) {
            throw new BusinessException("Команда доступна только оператору.");
        }
    }

    public PrintOrder newDraft(long user) {
        orders.lock();
        if (!orders.consented(user)) {
            throw new BusinessException("Сначала ознакомьтесь с условиями: /start, затем нажмите «Принимаю условия».");
        }
        PrintOrder draft = orders.draft(user);
        if (draft == null) {
            draft = orders.create(user, clock.instant());
        }
        return draft;
    }

    public PrintOrder attach(long user, String fileId, String name, byte[] bytes) {
        PrintOrder draft = newDraft(user);
        ModelFile model = storage.save(orders.customerId(user), fileId, name, bytes);
        orders.model(draft.id(), model);
        orders.event(draft.id(), user, "MODEL_UPLOADED", model.sha256(), clock.instant());
        return orders.get(draft.id());
    }

    public PrintOrder configure(long user, UUID id, String field, String value) {
        orders.lock();
        PrintOrder order = owned(user, id);
        order.status().require(OrderStatus.DRAFT);
        String material = order.materialId();
        String profile = order.profile();
        int infill = order.infill();
        int quantity = order.quantity();
        String promo = order.promoCode();
        switch (field) {
            case "material" -> {
                Material selected = properties.materials().get(value);
                if (selected == null || !selected.available()) {
                    throw new BusinessException("Этот материал сейчас недоступен.");
                }
                material = value;
            }
            case "profile" -> {
                if (!List.of("STANDARD", "FINE").contains(value)) {
                    throw new BusinessException("Выберите STANDARD или FINE.");
                }
                profile = value;
            }
            case "infill" -> {
                infill = integer(value);
                if (!List.of(15, 30, 60).contains(infill)) {
                    throw new BusinessException("Заполнение: 15, 30 или 60 процентов.");
                }
            }
            case "quantity" -> {
                quantity = integer(value);
                if (quantity < 1 || quantity > 100) {
                    throw new BusinessException("Количество: от 1 до 100 штук.");
                }
            }
            case "promo" -> {
                promo = value.toUpperCase(Locale.ROOT);
                if (promo.equals("-")) {
                    promo = "";
                }
                if (!promo.matches("[A-Z0-9_]{0,20}")) {
                    throw new BusinessException("Промокод: до 20 латинских букв, цифр или знаков подчёркивания.");
                }
            }
            default -> throw new BusinessException("Неизвестная настройка.");
        }
        orders.options(id, material, profile, infill, quantity, promo);
        return orders.get(id);
    }

    public void submit(long user, UUID id) {
        orders.lock();
        PrintOrder order = owned(user, id);
        if (order.status() == OrderStatus.ESTIMATING) {
            return;
        }
        order.status().require(OrderStatus.DRAFT);
        if (order.modelId() == null || order.materialId() == null) {
            throw new BusinessException("Загрузите STL и выберите материал.");
        }
        Material material = properties.materials().get(order.materialId());
        if (material == null || !material.available()) {
            throw new BusinessException("Выбранный материал закончился. Выберите другой в черновике.");
        }
        change(order, user, OrderStatus.ESTIMATING, "Клиент отправил заявку");
        if (slicerProperties.enabled()) {
            slicing.enqueue(order.id(), clock.instant());
            notifications.text(user, "Модель отправлена на автоматический расчёт. Время печати и расход получим из профиля принтера.");
            return;
        }
        ModelFile model = orders.model(order.modelId());
        notifications.text(user, "Заявка №" + order.number()
                + " получена. Оператор рассчитает печать и отправит вам цену. До подтверждения платить не нужно.");
        notifications.fileToAdmins(model.telegramFileId(), "Заказ №" + order.number() + "\n" + summary(order));
        notifications.admins("Новая заявка №" + order.number() + ". Проверьте размеры, ориентацию, поддержки и весь тираж в слайсере.\n"
                + "Ввод: /slice " + order.number() + " граммы минутыПечати минутыПодготовки минутыОбработки отходыГ стоимостьДоставки ценаДоставки [пластин]\n"
                + "Без доставки и дополнительных отходов: /slice " + order.number()
                + " 75 210\nЭто 75 г и 210 минут печати. Все величины — на весь тираж.");
    }

    public Quote estimate(long admin, long number, Estimate estimate) {
        requireAdmin(admin);
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        order.status().require(OrderStatus.ESTIMATING, OrderStatus.REVIEW_REQUIRED, OrderStatus.QUOTE_READY, OrderStatus.EXPIRED);
        return createQuote(admin, order, estimate);
    }

    public Quote automaticEstimate(UUID orderId, Estimate estimate, String provenance) {
        orders.lock();
        PrintOrder order = orders.get(orderId);
        if (order.status() != OrderStatus.ESTIMATING) {
            return null;
        }
        orders.event(order.id(), 0, "AUTO_SLICED", provenance, clock.instant());
        return createQuote(0, order, estimate);
    }

    private Quote createQuote(long admin, PrintOrder order, Estimate estimate) {
        estimate.validate();
        if (estimate.plateCount() > order.quantity()) {
            throw new BusinessException("Число пластин не может превышать число изделий в этом заказе.");
        }
        boolean newCustomer = orders.paidCount(order.customerId()) == 0;
        PricingSnapshot candidate = pricing.calculate(order.materialId(), estimate, newCustomer, true, "");
        Instant now = clock.instant();
        PromotionResult promotion = promotions.check(order, newCustomer, candidate.publicPrice().discount(), now);
        PricingSnapshot snapshot = pricing.calculate(order.materialId(), estimate, newCustomer, promotion.applied(), promotion.note());
        Instant expires = now.plus(properties.rates().quoteHours(), ChronoUnit.HOURS);
        if (promotion.applied() && properties.promotion().endsAt().isBefore(expires)) {
            expires = properties.promotion().endsAt();
        }
        Quote quote = new Quote(UUID.randomUUID(), order.id(), expires, snapshot, null, null);
        reservations.release(order.id());
        if (promotion.applied()) {
            reservations.reserve(order.id(), order.customerId(), properties.promotion().code(), snapshot.publicPrice().discount(), expires);
        }
        OrderStatus status = OrderStatus.QUOTE_READY;
        if (snapshot.economics().needsReview()) {
            status = OrderStatus.REVIEW_REQUIRED;
        }
        orders.saveQuote(quote, status);
        orders.event(order.id(), admin, "ESTIMATED", "Тариф " + snapshot.tariffVersion(), now);
        notifications.admins(internalSummary(order, quote));
        if (status == OrderStatus.REVIEW_REQUIRED) {
            notifications.text(order.customerId(), "Заказ №" + order.number() + ": оператор уточняет условия. Цена пока не согласована.");
        } else {
            notifications.quote(order, quote);
        }
        return quote;
    }

    public void approve(long admin, long number, String reason) {
        requireAdmin(admin);
        requireReason(reason);
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        order.status().require(OrderStatus.REVIEW_REQUIRED);
        Quote quote = orders.quote(order.quoteId());
        active(quote);
        checkReservation(order, quote);
        orders.approve(quote.id(), admin, reason);
        orders.status(order.id(), OrderStatus.QUOTE_READY);
        orders.event(order.id(), admin, "FINANCIAL_OVERRIDE", reason, clock.instant());
        notifications.quote(order, quote);
    }

    public void accept(long user, UUID quoteId) {
        orders.lock();
        Quote quote = orders.quote(quoteId);
        PrintOrder order = owned(user, quote.orderId());
        if (!quoteId.equals(order.quoteId())) {
            throw new BusinessException("Это старое предложение. Откройте /orders и подтвердите актуальную цену.");
        }
        if (order.status() == OrderStatus.AWAITING_PAYMENT) {
            active(quote);
            return;
        }
        order.status().require(OrderStatus.QUOTE_READY);
        active(quote);
        checkReservation(order, quote);
        if (quote.snapshot().economics().needsReview() && quote.approvedBy() == null) {
            throw new BusinessException("Дождитесь согласования оператором.");
        }
        orders.status(order.id(), OrderStatus.AWAITING_PAYMENT);
        orders.event(order.id(), user, "ACCEPTED", quote.id().toString(), clock.instant());
        notifications.text(user, "Заказ №" + order.number() + " подтверждён. К оплате "
                + Money.format(quote.snapshot().publicPrice().total()) + ".\n" + properties.paymentInstructions()
                + "\nОплатите до срока из предложения. Если срок прошёл, сначала запросите новый расчёт."
                + "\nПосле перевода оператор проверит поступление. Вопросы: " + properties.contact());
        notifications.admins("Клиент подтвердил заказ №" + order.number() + ". Ожидается оплата.");
    }

    public void paid(long admin, long number, BigDecimal amount, String reference) {
        requireAdmin(admin);
        reference(reference);
        Money.nonNegative(amount, "Оплата");
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        if (order.quoteId() == null) {
            throw new BusinessException("У заказа нет согласованной цены.");
        }
        Quote quote = orders.quote(order.quoteId());
        if (amount.compareTo(quote.snapshot().publicPrice().total()) != 0) {
            throw new BusinessException("Сумма поступления должна точно совпадать с предложением: "
                    + Money.format(quote.snapshot().publicPrice().total()) + ".");
        }
        String existing = payments.reference(order.id());
        if (existing != null) {
            if (existing.equals(reference)) {
                return;
            }
            throw new BusinessException("Оплата уже зарегистрирована с другим идентификатором.");
        }
        if (payments.referenceUsed(reference)) {
            throw new BusinessException("Этот перевод уже учтён в другом заказе.");
        }
        order.status().require(OrderStatus.AWAITING_PAYMENT);
        active(quote);
        checkReservation(order, quote);
        payments.paid(order, amount, reference, orders.customerId(admin), clock.instant());
        reservations.redeem(order.id());
        orders.event(order.id(), admin, "PAID", reference, clock.instant());
        notifications.text(order.customerId(), "Оплата заказа №" + number + " подтверждена. Заказ в очереди на печать.");
    }

    public void production(long admin, long number, OrderStatus status) {
        requireAdmin(admin);
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        if (order.status() == status) {
            return;
        }
        order.status().requireProductionTransition(status);
        change(order, admin, status, "Обновление производства");
    }

    public void cancel(long actor, UUID orderId) {
        orders.lock();
        PrintOrder order = orders.get(orderId);
        if (!order.customerId().equals(orders.customerId(actor)) && !isAdmin(actor)) {
            throw new BusinessException("Заказ не найден.");
        }
        if (order.status() == OrderStatus.CANCELLED) {
            return;
        }
        order.status().require(OrderStatus.DRAFT, OrderStatus.ESTIMATING, OrderStatus.REVIEW_REQUIRED,
                OrderStatus.QUOTE_READY, OrderStatus.AWAITING_PAYMENT, OrderStatus.EXPIRED);
        reservations.release(orderId);
        change(order, actor, OrderStatus.CANCELLED, "Отмена до оплаты");
        notifications.admins("Заказ №" + order.number() + " отменён до оплаты.");
    }

    public void refund(long admin, long number, String refundReference) {
        requireAdmin(admin);
        reference(refundReference);
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        if (order.status() == OrderStatus.REFUNDED && refundReference.equals(payments.refundReference(order.id()))) {
            return;
        }
        order.status().require(OrderStatus.PAID, OrderStatus.PRINTING, OrderStatus.QUALITY_CHECK,
                OrderStatus.READY, OrderStatus.COMPLETED);
        if (payments.referenceUsed(refundReference)) {
            throw new BusinessException("Идентификатор перевода уже использован.");
        }
        payments.refund(order.id(), refundReference, clock.instant());
        change(order, admin, OrderStatus.REFUNDED, "Полный возврат: " + refundReference);
    }

    public void expire() {
        orders.lock();
        for (PrintOrder order : orders.expired(clock.instant())) {
            reservations.release(order.id());
            change(order, 0, OrderStatus.EXPIRED, "Срок предложения истёк");
        }
        reservations.releaseExpired(clock.instant());
    }

    public void reslice(long admin, long number) {
        requireAdmin(admin);
        orders.lock();
        if (!slicerProperties.enabled()) {
            throw new BusinessException("Автоматический слайсер не настроен.");
        }
        PrintOrder order = orders.byNumber(number);
        order.status().require(OrderStatus.ESTIMATING, OrderStatus.EXPIRED, OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
        reservations.release(order.id());
        orders.status(order.id(), OrderStatus.ESTIMATING);
        slicing.enqueue(order.id(), clock.instant());
        orders.event(order.id(), admin, "RESLICE", "Повторный автоматический расчёт", clock.instant());
        notifications.text(order.customerId(), "Заказ №" + number + " отправлен на повторный расчёт.");
    }

    public PrintOrder owned(long user, UUID id) {
        PrintOrder order = orders.get(id);
        if (!order.customerId().equals(orders.customerId(user))) {
            throw new BusinessException("Заказ не найден.");
        }
        return order;
    }

    public String summary(PrintOrder order) {
        String material = "не выбран";
        if (order.materialId() != null) {
            Material configured = properties.materials().get(order.materialId());
            material = order.materialId();
            if (configured != null) {
                material = configured.label();
            }
        }
        return "Материал: " + material + "\nПрофиль: " + order.profile() + "\nЗаполнение: " + order.infill()
                + "%\nКоличество: " + order.quantity() + " шт.\nПромокод: " + order.promoCode();
    }

    public String internalSummary(PrintOrder order, Quote quote) {
        PricingSnapshot snapshot = quote.snapshot();
        String text = "Расчёт №" + order.number() + " · " + snapshot.tariffVersion()
                + "\nК оплате: " + Money.format(snapshot.publicPrice().total())
                + "\nЗатраты без процентной комиссии и налога: " + Money.format(snapshot.costs().total())
                + "\nПластик: " + Money.format(snapshot.costs().material())
                + "; резерв брака: " + Money.format(snapshot.costs().failureReserve())
                + "\nКомиссия (%): " + Money.format(snapshot.economics().paymentFee())
                + "; налог: " + Money.format(snapshot.economics().estimatedTax())
                + "\nМаржинальный доход: " + Money.format(snapshot.economics().contribution())
                + "; минимум: " + Money.format(snapshot.economics().minimumContribution());
        if (snapshot.economics().profitMarkup() != null) {
            BigDecimal percent = snapshot.economics().profitMarkup().multiply(new BigDecimal("100"));
            BigDecimal minimumPercent = snapshot.economics().minimumProfitMarkup().multiply(new BigDecimal("100"));
            String profitLabel = "Доход к полным учтённым затратам";
            if (snapshot.economics().printingContribution() != null) {
                text += "\nДоход печати сверх сбора за запуск: " + Money.format(snapshot.economics().printingContribution())
                        + "; сбор за запуск: " + Money.format(snapshot.publicPrice().launchFee());
                profitLabel = "Доход печати к полным учтённым затратам (без сбора за запуск)";
            }
            text += "\n" + profitLabel + ": " + Money.rub(percent).toPlainString()
                    + "%; минимум: " + Money.rub(minimumPercent).toPlainString() + "%"
                    + ". Затраты с комиссией и налогом: " + Money.format(snapshot.economics().totalCost());
        }
        if (snapshot.economics().needsReview()) {
            text += "\nНиже финансового порога. Для осознанного согласования: /approve " + order.number()
                    + " причина. Можно пересчитать /slice или отменить /cancel " + order.number() + ".";
        }
        return text;
    }

    private void checkReservation(PrintOrder order, Quote quote) {
        if (quote.snapshot().publicPrice().discount().signum() > 0
                && (!reservations.hasReservation(order.id(), clock.instant()) || orders.paidCount(order.customerId()) > 0)) {
            throw new BusinessException("Резерв акции недействителен. Оператор должен сформировать новое предложение.");
        }
    }

    private void active(Quote quote) {
        if (!clock.instant().isBefore(quote.expiresAt())) {
            throw new BusinessException("Срок предложения истёк. Попросите оператора пересчитать заказ.");
        }
    }

    private void change(PrintOrder order, long actor, OrderStatus status, String detail) {
        orders.status(order.id(), status);
        orders.event(order.id(), actor, status.name(), detail, clock.instant());
        notifications.text(order.customerId(), "Заказ №" + order.number() + ": " + status.label() + ".");
    }

    private void reference(String value) {
        if (!value.matches("[a-zA-Z0-9_./:-]{3,128}")) {
            throw new BusinessException("Идентификатор перевода: 3–128 латинских букв, цифр или символов _ . / : -.");
        }
    }

    private void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5 || reason.length() > 500) {
            throw new BusinessException("Укажите причину согласования: от 5 до 500 символов.");
        }
    }

    private int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException("Нужно целое число.");
        }
    }
}
