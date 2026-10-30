package ru.printlab.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Money;
import ru.printlab.catalog.ColorService;
import ru.printlab.catalog.FilamentColor;
import ru.printlab.config.AppProperties;
import ru.printlab.config.Material;
import ru.printlab.config.PromotionPolicy;
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
import ru.printlab.operator.OperatorAccess;

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
    private final OperatorAccess operators;
    private final ColorService colors;
    private final DeliveryDao deliveries;
    private final ru.printlab.catalog.ColorRequestDao colorRequests;

    public OrderService(OrderDao orders, PaymentDao payments, PricingService pricing, PromotionService promotions,
                        PromotionDao reservations, AppProperties properties, ModelStorage storage,
                        Notifications notifications, Clock clock, SlicingDao slicing, SlicerProperties slicerProperties,
                        OperatorAccess operators, ColorService colors, DeliveryDao deliveries, ru.printlab.catalog.ColorRequestDao colorRequests) {
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
        this.operators = operators;
        this.colors = colors;
        this.deliveries = deliveries;
        this.colorRequests = colorRequests;
    }

    public boolean isAdmin(long user) {
        return operators.isAdmin(user);
    }

    public void requireAdmin(long user) {
        if (!isAdmin(user)) {
            throw new BusinessException("Команда доступна только оператору.");
        }
    }

    public PrintOrder newDraft(long user) {
        return newDraft(user, false);
    }

    public PrintOrder newDraft(long user, boolean fresh) {
        orders.lock();
        if (!orders.consented(user)) {
            throw new BusinessException("Открой главное меню и нажми «Рассчитать печать по STL», чтобы продолжить.");
        }
        PrintOrder draft = null;
        if (!fresh) draft = orders.draft(user);
        if (draft == null) {
            draft = orders.create(user, clock.instant());
        }
        return draft;
    }

    public PrintOrder attach(long user, String fileId, String name, byte[] bytes) {
        return attachTo(user, newDraft(user).id(), fileId, name, bytes);
    }

    public PrintOrder attachTo(long user, UUID id, String fileId, String name, byte[] bytes) {
        orders.lock();
        PrintOrder draft = owned(user, id);
        draft.status().require(OrderStatus.DRAFT);
        ModelFile model = storage.save(draft.customerId(), fileId, name, bytes);
        orders.model(draft.id(), model);
        orders.event(draft.id(), user, "MODEL_UPLOADED", model.sha256(), clock.instant());
        return orders.get(draft.id());
    }

    public PrintOrder configure(long user, UUID id, String field, String value) {
        if (field.equals("promo")) {
            PromotionResult result = applyPromotion(user, id, value);
            if (!result.applied() && !value.equals("-") && !value.isBlank()) throw new BusinessException(result.note());
            return orders.get(id);
        }
        orders.lock();
        PrintOrder order = owned(user, id);
        order.status().require(OrderStatus.DRAFT);
        String material = order.materialId();
        String profile = order.profile();
        int infill = order.infill();
        int quantity = order.quantity();
        String promo = order.promoCode();
        UUID colorId = order.colorId();
        BigDecimal layer = order.layerHeightMm();
        switch (field) {
            case "color" -> {
                FilamentColor selected = selectedColor(order, UUID.fromString(value));
                colorId = selected.id();
                material = "pla-black";
            }
            case "material" -> {
                Material selected = properties.materials().get(value);
                if (selected == null || !selected.available()) {
                    throw new BusinessException("Этот материал сейчас недоступен.");
                }
                material = value;
            }
            case "profile" -> {
                if (!List.of("STANDARD", "FINE").contains(value)) {
                    throw new BusinessException("Выбери стандартную или повышенную детализацию.");
                }
                profile = value;
                layer = new BigDecimal("0.20");
                if (profile.equals("FINE")) {
                    layer = new BigDecimal("0.12");
                }
            }
            case "layer" -> {
                try {
                    layer = new BigDecimal(value.replace(',', '.'));
                } catch (NumberFormatException exception) {
                    throw new BusinessException("Не получилось распознать число. Попробуй ввести его ещё раз.");
                }
                if (layer.compareTo(new BigDecimal("0.08")) < 0 || layer.compareTo(new BigDecimal("0.28")) > 0
                        || layer.stripTrailingZeros().scale() > 2) {
                    throw new BusinessException("Для нашего принтера подойдёт высота слоя от 0,08 до 0,28 мм, до двух знаков после запятой.");
                }
                profile = "CUSTOM";
            }
            case "infill" -> {
                infill = integer(value);
                if (infill < 1 || infill > 100) {
                    throw new BusinessException("Заполнение должно быть целым числом от 1 до 100%.");
                }
            }
            case "quantity" -> {
                quantity = integer(value);
                if (quantity < 1 || quantity > 100) {
                    throw new BusinessException("Можно заказать от 1 до 100 деталей.");
                }
            }
            case "promo" -> {
                promo = value.toUpperCase(Locale.ROOT);
                if (promo.equals("-")) {
                    promo = "";
                }
                if (!promo.matches("[A-Z0-9_]{0,20}")) {
                    throw new BusinessException("В промокоде может быть до 20 латинских букв, цифр или знаков подчёркивания.");
                }
            }
            default -> throw new BusinessException("Неизвестная настройка.");
        }
        orders.options(id, material, profile, infill, quantity, promo, colorId, layer);
        if (field.equals("color")) colorRequests.supersede(id);
        return orders.get(id);
    }

    public PromotionResult applyPromotion(long user, UUID id, String value) {
        orders.lock();
        PrintOrder order = owned(user, id);
        order.status().require(OrderStatus.DRAFT);
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (code.equals("-") || code.isBlank()) {
            reservations.release(id);
            orders.promo(id, "");
            return new PromotionResult(false, "Промокод убран.");
        }
        if (!code.matches("[A-Z0-9_]{1,20}")) return new PromotionResult(false, "Промокод не найден.");
        PromotionResult result = promotions.eligibility(order, code, clock.instant());
        if (result.applied()) {
            orders.promo(id, code);
            orders.event(id, user, "PROMO_APPLIED", result.note(), clock.instant());
        }
        return result;
    }

    public void discardDraft(long user, UUID id) {
        orders.lock();
        PrintOrder order = owned(user, id);
        order.status().require(OrderStatus.DRAFT, OrderStatus.MODELING);
        reservations.release(order.id());
        orders.status(order.id(), OrderStatus.CANCELLED);
        orders.deleted(order.id(), true);
        orders.event(order.id(), user, "DRAFT_DISCARDED", "Новый заказ вместо незавершённого черновика", clock.instant());
    }

    public void delete(long admin, UUID id) {
        requireAdmin(admin);
        orders.lock();
        PrintOrder order = orders.get(id);
        if (order.deleted()) return;
        if (order.paidAt() == null && order.status() != OrderStatus.CANCELLED) cancel(admin, id);
        orders.deleted(id, true);
        orders.event(id, admin, "ORDER_DELETED", "Удалён из активного списка с возможностью восстановления", clock.instant());
    }

    public void restore(long admin, UUID id) {
        requireAdmin(admin);
        orders.lock();
        PrintOrder order = orders.get(id);
        if (!order.deleted()) return;
        orders.deleted(id, false);
        orders.event(id, admin, "ORDER_RESTORED", "Восстановлен из удалённых заказов", clock.instant());
    }

    public void submit(long user, UUID id) {
        orders.lock();
        PrintOrder order = owned(user, id);
        if (order.status() == OrderStatus.ESTIMATING) {
            return;
        }
        order.status().require(OrderStatus.DRAFT);
        if (order.modelId() == null || order.materialId() == null || order.colorId() == null) {
            throw new BusinessException("Сначала загрузи STL и выбери доступный цвет.");
        }
        selectedColor(order, order.colorId());
        Material material = properties.materials().get(order.materialId());
        if (material == null || !material.available()) {
            throw new BusinessException("Этот материал закончился. Выбери другой цвет в параметрах заказа.");
        }
        change(order, user, OrderStatus.ESTIMATING, "Клиент отправил заявку");
        ModelFile model = orders.model(order.modelId());
        notifications.fileToAdmins(order, model.telegramFileId(), "Заказ №" + order.number() + "\n" + summary(order));
        if (slicerProperties.enabled()) {
            slicing.enqueue(order.id(), clock.instant());
            notifications.admins("Новая заявка №" + order.number() + " отправлена на автоматический расчёт.");
            notifications.text(user, "Считаем стоимость заказа №" + order.number() + ". Обычно это занимает несколько минут. Как только расчёт будет готов, пришлём результат сюда.");
            if (order.quantity() > 1) {
                notifications.text(user, "Расчёт тиража: каждое изделие печатается на отдельной пластине. "
                        + "Совместное размещение и пересчёт можно согласовать с оператором до оплаты.");
            }
            return;
        }
        notifications.text(user, "Заявка №" + order.number()
                + " получена. Оператор рассчитает печать и отправит вам цену. До подтверждения платить не нужно.");
        notifications.admins("Новая заявка №" + order.number() + ". Проверьте размеры, ориентацию, поддержки и весь тираж в слайсере.\n"
                + "Ввод: /slice " + order.number() + " граммы минутыПечати минутыПодготовки минутыОбработки отходыГ стоимостьДоставки ценаДоставки [пластин]\n"
                + "Без доставки и дополнительных отходов: /slice " + order.number()
                + " 75 210\nЭто 75 г и 210 минут печати. Все величины относятся ко всему тиражу.");
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
        // Carrier charges are paid separately and never enter new PrintLab receipts or costs.
        estimate = new Estimate(estimate.grams(), estimate.extraWasteGrams(), estimate.printMinutes(),
                estimate.setupMinutes(), estimate.finishingMinutes(), BigDecimal.ZERO, BigDecimal.ZERO, estimate.plateCount());
        boolean newCustomer = orders.paidCount(order.customerId()) == 0;
        PromotionPolicy policy = promotions.policy(order.promoCode());
        Material material = properties.materials().get(order.materialId());
        FilamentColor color = colors.historical(order.colorId());
        material = new Material(material.label(), color.costPerKg(), material.available());
        PricingSnapshot candidate = pricing.calculate(material, estimate, newCustomer, true, "", policy);
        Instant now = clock.instant();
        PromotionResult promotion = promotions.check(order, candidate.publicPrice().discount(), now);
        PricingSnapshot snapshot = pricing.calculate(material, estimate, newCustomer, promotion.applied(), promotion.note(), policy)
                .withColor(color);
        Instant expires = now.plus(properties.rates().quoteHours(), ChronoUnit.HOURS);
        if (promotion.applied() && policy.endsAt().isBefore(expires)) expires = policy.endsAt();
        Quote quote = new Quote(UUID.randomUUID(), order.id(), expires, snapshot, null, null);
        reservations.release(order.id());
        orders.saveQuote(quote, OrderStatus.REVIEW_REQUIRED);
        DeliveryDetails delivery = deliveries.get(order.id());
        if (delivery != null && delivery.externalPrice() != null) deliveries.priced(order.id(), quote.id(), delivery.externalPrice());
        orders.event(order.id(), admin, "ESTIMATED", "Тариф " + snapshot.tariffVersion(), now);
        notifications.adminOrder(orders.get(order.id()), internalSummary(order, quote));
        notifications.quote(orders.get(order.id()), quote);
        return quote;
    }

    public void approve(long admin, long number, String reason) {
        requireAdmin(admin);
        requireReason(reason);
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        order.status().require(OrderStatus.REVIEW_REQUIRED);
        if (order.quoteId() == null) throw new BusinessException("Сначала нужно завершить расчёт модели.");
        Quote quote = orders.quote(order.quoteId());
        active(quote);
        checkPromotion(order, quote);
        DeliveryDetails delivery = deliveries.get(order.id());
        if (delivery == null || !quote.id().equals(delivery.pricedQuoteId()))
            throw new BusinessException("Сначала дождитесь способа получения и укажите отдельный тариф СДЭК либо самовывоз.");
        // Requote a legacy unpaid offer that previously included delivery in our receipt.
        if (quote.snapshot().publicPrice().deliveryPrice().signum() > 0 || quote.snapshot().costs().delivery().signum() > 0) {
            BigDecimal carrier = delivery.externalPrice();
            if (carrier == null) carrier = quote.snapshot().publicPrice().deliveryPrice();
            quote = new Quote(UUID.randomUUID(), order.id(), quote.expiresAt(), pricing.withDelivery(quote.snapshot(), carrier), null, null);
            orders.saveQuote(quote, OrderStatus.REVIEW_REQUIRED);
            deliveries.priced(order.id(), quote.id(), carrier);
        }
        orders.approve(quote.id(), admin, reason);
        orders.status(order.id(), OrderStatus.QUOTE_READY);
        orders.event(order.id(), admin, "PRICE_PUBLISHED", reason, clock.instant());
        notifications.quote(orders.get(order.id()), orders.quote(quote.id()));
    }

    public Quote editPrice(long admin, UUID id, UUID expected, BigDecimal amount, String reason) {
        requireAdmin(admin);
        requireReason(reason);
        Money.nonNegative(amount, "Цена печати");
        if (amount.scale() > 2 || amount.compareTo(new BigDecimal("10000000")) > 0)
            throw new BusinessException("Цена должна быть от 0 до 10000000 ₽, до двух знаков после запятой.");
        orders.lock();
        PrintOrder order = orders.get(id);
        if (order.deleted()) throw new BusinessException("Сначала восстановите заказ.");
        order.status().require(OrderStatus.REVIEW_REQUIRED, OrderStatus.QUOTE_READY, OrderStatus.AWAITING_PAYMENT);
        if (!expected.equals(order.quoteId())) throw new BusinessException("Расчёт изменился. Откройте заказ заново.");
        Quote previous = orders.quote(expected);
        active(previous);
        BigDecimal discount = amount.multiply(previous.snapshot().promotion().rate());
        PromotionResult promotion = promotions.check(order, discount, clock.instant());
        PricingSnapshot snapshot = pricing.withPrintPrice(previous.snapshot(), amount, promotion.applied(), promotion.note());
        Quote quote = new Quote(UUID.randomUUID(), id, previous.expiresAt(), snapshot, null, null);
        orders.saveQuote(quote, OrderStatus.REVIEW_REQUIRED);
        DeliveryDetails delivery = deliveries.get(id);
        if (delivery != null && previous.id().equals(delivery.pricedQuoteId())) {
            BigDecimal carrier = delivery.externalPrice();
            if (carrier == null) carrier = previous.snapshot().publicPrice().deliveryPrice();
            deliveries.priced(id, quote.id(), carrier);
        }
        orders.event(id, admin, "PRINT_PRICE_EDITED", "Цена до скидки: " + amount.toPlainString() + "; " + reason, clock.instant());
        notifications.adminOrder(orders.get(id), internalSummary(order, quote));
        notifications.quote(orders.get(id), quote);
        return quote;
    }

    public void accept(long user, UUID quoteId) {
        orders.lock();
        Quote quote = orders.quote(quoteId);
        PrintOrder order = owned(user, quote.orderId());
        if (!quoteId.equals(order.quoteId())) {
            throw new BusinessException("Этот расчёт уже неактуален. Открой заказ заново и проверь итоговую сумму.");
        }
        if (order.status() == OrderStatus.AWAITING_PAYMENT) {
            active(quote);
            return;
        }
        order.status().require(OrderStatus.QUOTE_READY);
        DeliveryDetails delivery = deliveries.get(order.id());
        if (delivery == null || !quote.id().equals(delivery.pricedQuoteId())) {
            throw new BusinessException("Сначала выбери доставку. Мы рассчитаем её стоимость и пришлём итоговую сумму.");
        }
        active(quote);
        checkPromotion(order, quote);
        if (quote.snapshot().economics().needsReview() && quote.approvedBy() == null) {
            throw new BusinessException("Дождись согласования команды PrintLab.");
        }
        orders.status(order.id(), OrderStatus.AWAITING_PAYMENT);
        orders.event(order.id(), user, "ACCEPTED", quote.id().toString(), clock.instant());
        notifications.text(user, "Заказ №" + order.number() + " подтверждён командой PrintLab и ожидает оплаты.\n\nСкоро команда PrintLab пришлёт тебе реквизиты в этом чате.\n\nКогда деньги поступят, мы подтвердим оплату и обновим статус здесь.");
        notifications.adminOrder(orders.get(order.id()), "Клиент подтвердил заказ №" + order.number()
                + ". Ожидается оплата.\n\nПришлите реквизиты через «Ответить клиенту».\n" + delivery.summary());
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
        checkPromotion(order, quote);
        payments.paid(order, amount, reference, orders.customerId(admin), clock.instant());
        if (quote.snapshot().publicPrice().discount().signum() > 0) {
            reservations.reserve(order.id(), order.customerId(), order.promoCode(), quote.snapshot().publicPrice().discount(), quote.expiresAt());
            reservations.redeem(order.id());
        }
        orders.event(order.id(), admin, "PAID", reference, clock.instant());
        notifications.text(order.customerId(), "Оплата заказа №" + number + " подтверждена! Скоро сообщим примерный срок готовности.");
        notifications.adminOrder(orders.get(order.id()), "Оплата заказа №" + number + " учтена.");
    }

    public void production(long admin, long number, OrderStatus status) {
        requireAdmin(admin);
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        if (order.deleted()) throw new BusinessException("Заказ удалён. Сначала восстановите его.");
        DeliveryDetails delivery = deliveries.get(order.id());
        if (status == OrderStatus.SHIPPED && delivery != null && delivery.method().equals("SELF_PICKUP")) {
            throw new BusinessException("Для самовывоза выберите «Выполнен» после выдачи заказа клиенту.");
        }
        if (order.status() == status) {
            return;
        }
        order.status().requireProductionTransition(status);
        change(order, admin, status, "Обновление производства");
    }

    public void delivery(long user, UUID id, String method, String fullName, String phone, String address) {
        orders.lock();
        PrintOrder order = owned(user, id);
        order.status().require(OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
        if (order.quoteId() == null) throw new BusinessException("Сначала нужно рассчитать стоимость печати.");
        active(orders.quote(order.quoteId()));
        if (!List.of("COURIER", "PICKUP").contains(method) || fullName.isBlank() || fullName.length() > 200
                || !phone.matches("\\+[1-9][0-9]{9,14}") || address.isBlank() || address.length() > 1000) {
            throw new BusinessException("Проверь ФИО, телефон с кодом страны и полный адрес доставки.");
        }
        order = unpublishDeliveryChange(order);
        DeliveryDetails delivery = new DeliveryDetails(id, method, fullName, phone, address, null);
        deliveries.save(delivery);
        orders.event(id, user, "DELIVERY_DETAILS", delivery.summary(), clock.instant());
        notifications.buttons(user, "Данные получили! Уточним тариф СДЭК и подтвердим цену заказа.\n\nДоставку ты оплачиваешь СДЭК отдельно: в приложении, на сайте или при получении.", List.of(
                Notifications.button("Изменить доставку", "shipping:" + id),
                Notifications.button("Мои заказы", "nav:orders"), Notifications.button("В меню", "nav:home")));
        notifications.adminOrder(order, "Нужно рассчитать доставку заказа №" + order.number() + ".\n\n" + delivery.summary());
    }

    public void selfPickup(long user, UUID id, String key) {
        orders.lock();
        PrintOrder order = owned(user, id);
        order.status().require(OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
        if (order.quoteId() == null) throw new BusinessException("Сначала нужно рассчитать модель.");
        active(orders.quote(order.quoteId()));
        ReceivingPoints.Point point = ReceivingPoints.get(key);
        order = unpublishDeliveryChange(order);
        DeliveryDetails delivery = new DeliveryDetails(id, "SELF_PICKUP", null, null, point.address(), order.quoteId(), BigDecimal.ZERO, key);
        deliveries.save(delivery);
        orders.event(id, user, "SELF_PICKUP", key, clock.instant());
        notifications.adminOrder(order, "Самовывоз заказа №" + order.number() + ".\n\n" + delivery.summary());
        notifications.buttons(user, "Выбран бесплатный самовывоз: " + point.label() + ".\n" + point.address()
                + "\n\nОжидай подтверждения заказа командой PrintLab. После него можно будет оплатить заказ.", List.of(
                java.util.Map.of("text", "ссылка на адрес", "url", point.mapUrl()),
                Notifications.button("Изменить способ получения", "shipping:" + id), Notifications.button("В меню", "nav:home")));
    }

    private PrintOrder unpublishDeliveryChange(PrintOrder order) {
        if (order.status() == OrderStatus.QUOTE_READY) {
            Quote previous = orders.quote(order.quoteId());
            Quote quote = new Quote(UUID.randomUUID(), order.id(), previous.expiresAt(), pricing.withDelivery(previous.snapshot(), BigDecimal.ZERO), null, null);
            orders.saveQuote(quote, OrderStatus.REVIEW_REQUIRED);
            return orders.get(order.id());
        }
        return order;
    }

    public Quote priceDelivery(long admin, UUID id, UUID expectedQuote, BigDecimal amount) {
        requireAdmin(admin);
        orders.lock();
        PrintOrder order = orders.get(id);
        order.status().require(OrderStatus.QUOTE_READY, OrderStatus.REVIEW_REQUIRED);
        if (!expectedQuote.equals(order.quoteId())) {
            throw new BusinessException("Расчёт уже изменился. Открой заказ заново.");
        }
        Quote previous = orders.quote(expectedQuote);
        active(previous);
        checkPromotion(order, previous);
        DeliveryDetails delivery = deliveries.get(id);
        if (delivery == null || delivery.method().equals("SELF_PICKUP")) {
            throw new BusinessException("Для тарифа СДЭК нужны данные доставки. Самовывоз бесплатный.");
        }
        Money.nonNegative(amount, "Доставка");
        if (amount.scale() > 2 || amount.compareTo(new BigDecimal("100000")) > 0) {
            throw new BusinessException("Стоимость доставки: от 0 до 100000 ₽, до двух знаков после запятой.");
        }
        PricingSnapshot snapshot = pricing.withDelivery(previous.snapshot(), amount);
        Quote quote = new Quote(UUID.randomUUID(), id, previous.expiresAt(), snapshot, null, null);
        orders.saveQuote(quote, OrderStatus.REVIEW_REQUIRED);
        deliveries.priced(id, quote.id(), amount);
        orders.event(id, admin, "DELIVERY_PRICED", amount.toPlainString(), clock.instant());
        notifications.adminOrder(orders.get(id), internalSummary(order, quote));
        notifications.quote(orders.get(id), quote);
        return quote;
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
        order.status().require(OrderStatus.DRAFT, OrderStatus.MODELING, OrderStatus.ESTIMATING, OrderStatus.REVIEW_REQUIRED,
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
                OrderStatus.READY, OrderStatus.SHIPPED, OrderStatus.COMPLETED);
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
        if (order.deleted() || !order.customerId().equals(orders.customerId(user))) {
            throw new BusinessException("Заказ не найден.");
        }
        return order;
    }

    public String summary(PrintOrder order) {
        String color = "не выбран";
        if (order.colorId() != null) {
            if (order.quoteId() != null && orders.quote(order.quoteId()).snapshot().color() != null) {
                color = orders.quote(order.quoteId()).snapshot().color().label();
            } else {
                color = colors.historical(order.colorId()).label();
            }
        }
        String promo = order.promoCode();
        if (promo.isBlank()) {
            promo = "не указан";
        }
        return "Цвет: " + color + "\nВысота слоя: " + order.layerHeightMm().toPlainString().replace('.', ',') + " мм\nЗаполнение: " + order.infill()
                + "%\nКоличество: " + order.quantity() + " шт.\nПромокод: " + promo;
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
                + "; ориентир: " + Money.format(snapshot.economics().minimumContribution());
        text += "\nЭлектричество: " + Money.format(snapshot.costs().electricity())
                + "; амортизация: " + Money.format(snapshot.costs().depreciation())
                + "; обслуживание: " + Money.format(snapshot.costs().maintenance())
                + "\nУпаковка: " + Money.format(snapshot.costs().packaging());
        if (snapshot.color() != null && snapshot.color().spoolPrice() != null) {
            text += "\nКатушка: " + Money.format(snapshot.color().spoolPrice()) + " / " + snapshot.color().spoolWeightGrams() + " г"
                    + "\nРасход пластика с отходами: " + snapshot.estimate().grams().add(snapshot.estimate().extraWasteGrams()).stripTrailingZeros().toPlainString() + " г";
        }
        DeliveryDetails delivery = deliveries.get(order.id());
        if (delivery != null) text += "\n\n" + delivery.summary();
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
                    + "%; ориентир: " + Money.rub(minimumPercent).toPlainString() + "%"
                    + ". Затраты с комиссией и налогом: " + Money.format(snapshot.economics().totalCost());
        }
        if (snapshot.economics().needsReview()) {
            text += "\nНиже финансового ориентира. Убыток допустим после явного подтверждения цены: /approve " + order.number()
                    + " причина. Можно пересчитать /slice или отменить /cancel " + order.number() + ".";
        }
        return text;
    }

    private FilamentColor selectedColor(PrintOrder order, UUID id) {
        FilamentColor color = colors.get(id);
        if (!color.available() && !colorRequests.approved(order.id(), id))
            throw new BusinessException("Этот цвет сейчас недоступен. Выбери другой из каталога.");
        color.costPerKg();
        return color;
    }

    private void checkPromotion(PrintOrder order, Quote quote) {
        if (quote.snapshot().publicPrice().discount().signum() > 0) {
            PromotionResult eligibility = promotions.eligibility(order, order.promoCode(), clock.instant());
            if (!eligibility.applied()) throw new BusinessException(eligibility.note() + " Нужен новый расчёт.");
        }
    }

    private void active(Quote quote) {
        if (!clock.instant().isBefore(quote.expiresAt())) {
            throw new BusinessException("Срок расчёта истёк. Напиши нам, пересчитаем заказ.");
        }
    }

    private void change(PrintOrder order, long actor, OrderStatus status, String detail) {
        orders.status(order.id(), status);
        orders.event(order.id(), actor, status.name(), detail, clock.instant());
        String text = "Заказ №" + order.number();
        switch (status) {
            case ESTIMATING -> { return; }
            case PRINTING -> text += " в работе.";
            case READY -> {
                DeliveryDetails delivery = deliveries.get(order.id());
                if (delivery != null && delivery.method().equals("SELF_PICKUP")) {
                    text += " готов к самовывозу. Согласуй время получения с командой PrintLab.";
                } else {
                    text += " готов к отправке.";
                }
            }
            case SHIPPED -> text += " отправлен СДЭК.";
            case COMPLETED -> text += " выполнен. Спасибо, что выбрал PrintLab!";
            case CANCELLED -> text += " отменён.";
            default -> text += ": " + status.label() + ".";
        }
        notifications.text(order.customerId(), text);
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
            throw new BusinessException("Не получилось распознать число. Попробуй ввести его ещё раз.");
        }
    }
}
