package ru.printlab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ru.printlab.catalog.*;
import ru.printlab.common.BusinessException;
import ru.printlab.order.*;
import ru.printlab.pricing.Estimate;
import ru.printlab.telegram.OutboxDao;
import ru.printlab.telegram.TelegramMenu;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = {
        "printlab.rates.version=a1-mini-v6", "printlab.rates.material-sale-multiplier=10",
        "printlab.rates.electricity-per-kwh=13", "printlab.rates.printer-kw=0.15",
        "printlab.rates.printer-purchase-cost=20000", "printlab.rates.printer-lifetime-hours=2000",
        "printlab.rates.maintenance-per-hour=5", "printlab.rates.labor-per-hour=0",
        "printlab.rates.packaging=35", "printlab.rates.failure-probability=0.10",
        "printlab.rates.acquisition-per-new-customer=0", "printlab.rates.payment-rate=0.03",
        "printlab.rates.tax-rate=0.04", "printlab.rates.minimum-print-price=0",
        "printlab.rates.minimum-contribution=0", "printlab.rates.target-profit-markup=0.50",
        "printlab.rates.minimum-profit-markup=0.50"
})
class ApprovalPricingWorkflowTest extends BotTestSupport {
    @Autowired ColorService colors;
    @Autowired CustomColorService custom;
    @Autowired ColorRequestDao requests;
    @Autowired AccountingService accounting;
    @Autowired OutboxDao outbox;
    private static final UUID BLACK = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BEIGE = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Test
    void retailUsesSpoolPriceAndNetMassWhileKeepingAllInternalCostsAndFrozenHistory() {
        PrintOrder black = draft(222);
        Quote quote = estimate(222, black, grams("10", "60"));
        assertThat(quote.snapshot().publicPrice().regularPrintPrice()).isEqualByComparingTo("70");
        assertThat(quote.snapshot().costs().material()).isEqualByComparingTo("7");
        assertThat(quote.snapshot().costs().depreciation()).isEqualByComparingTo("10");
        assertThat(quote.snapshot().costs().maintenance()).isEqualByComparingTo("5");
        assertThat(quote.snapshot().costs().electricity()).isEqualByComparingTo("1.95");
        assertThat(quote.snapshot().costs().failureReserve()).isEqualByComparingTo("2.66");
        assertThat(quote.snapshot().costs().packaging()).isEqualByComparingTo("35");
        assertThat(quote.snapshot().economics().paymentFee()).isEqualByComparingTo("2.10");
        assertThat(quote.snapshot().economics().estimatedTax()).isEqualByComparingTo("2.80");
        assertThat(quote.snapshot().economics().contribution()).isEqualByComparingTo("3.49");
        FilamentColor original = colors.get(BLACK);
        colors.save(111, new FilamentColor(BLACK, original.name(), original.surface(), null, null, null, true, false, new BigDecimal("900"), 500));
        assertThat(orders.quote(quote.id()).snapshot().color().spoolPrice()).isEqualByComparingTo("700");
        assertThat(orders.quote(quote.id()).snapshot().material().costPerKg()).isEqualByComparingTo("700");
        PrintOrder beige = draft(333);
        service.configure(333, beige.id(), "color", BEIGE.toString());
        assertThat(estimate(333, beige, grams("10", "60")).snapshot().publicPrice().regularPrintPrice()).isEqualByComparingTo("110");
        PrintOrder revised = draft(444);
        assertThat(estimate(444, revised, grams("10", "60")).snapshot().publicPrice().regularPrintPrice()).isEqualByComparingTo("180");
    }

    @Test
    void manualPriceIsHiddenUntilExplicitApprovalAndStalePublishedOffersCannotBeAccepted() {
        PrintOrder order = draft(222);
        service.applyPromotion(222, order.id(), "TESTDATE");
        Quote initial = estimate(222, order, Fixtures.estimate());
        assertThat(messages(222)).doesNotContain("Печать:");
        assertThat(messages(-12345)).contains("К оплате:");
        assertThatThrownBy(() -> service.approve(111, order.number(), "Проверка цены"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("способа получения");
        service.selfPickup(222, order.id(), "baumanskaya");
        click(111, -12345, "op:price:" + order.id());
        group(111, Map.of("text", "0,50"));
        group(111, Map.of("text", "Привлечение первого клиента, убыток согласован"));
        PrintOrder revised = orders.get(order.id());
        Quote edited = orders.quote(revised.quoteId());
        assertThat(edited.id()).isNotEqualTo(initial.id());
        assertThat(edited.snapshot().publicPrice().regularPrintPrice()).isEqualByComparingTo("0.50");
        assertThat(edited.snapshot().publicPrice().discount()).isEqualByComparingTo("0.05");
        assertThat(edited.snapshot().publicPrice().total()).isEqualByComparingTo("0.45");
        assertThat(edited.snapshot().economics().contribution()).isNegative();
        assertThat(messages(222)).doesNotContain("Печать:");
        click(111, -12345, "op:publish:" + order.id());
        click(111, -12345, "op:publishConfirm:" + order.id());
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.QUOTE_READY);
        assertThat(messages(222)).contains("Печать: 0,50 ₽", "К оплате PrintLab: 0,45 ₽");
        service.accept(222, edited.id());
        Quote replacement = service.editPrice(111, order.id(), edited.id(), BigDecimal.ZERO, "Бесплатный тест печати согласован");
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        assertThat(replacement.snapshot().publicPrice().total()).isZero();
        assertThatThrownBy(() -> service.accept(222, edited.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.editPrice(111, order.id(), edited.id(), BigDecimal.ONE, "Устаревшая правка"))
                .isInstanceOf(BusinessException.class);
        assertThat(count("promotion_reservations")).isZero();
    }

    @Test
    void cdekIsAnExternalFeeAndNeverInOurReceiptOrActualCosts() {
        PrintOrder order = draft(222);
        Quote initial = estimate(222, order, grams("10", "60"));
        service.delivery(222, order.id(), "COURIER", "Тестовый Иван Иванович", "+79990000000", "Москва, Тестовая 1");
        Quote quote = service.priceDelivery(111, order.id(), initial.id(), new BigDecimal("1000"));
        service.approve(111, order.number(), "Проверена печать и отдельный тариф СДЭК");
        assertThat(delivery.get(order.id()).externalPrice()).isEqualByComparingTo("1000");
        assertThat(quote.snapshot().publicPrice().total()).isEqualByComparingTo("70");
        assertThat(quote.snapshot().publicPrice().deliveryPrice()).isZero();
        assertThat(quote.snapshot().costs()).isEqualTo(initial.snapshot().costs());
        assertThat(quote.snapshot().economics()).isEqualTo(initial.snapshot().economics());
        assertThat(messages(222)).contains("1000,00 ₽", "СДЭК отдельно", "К оплате PrintLab: 70,00 ₽");
        service.accept(222, quote.id());
        service.paid(111, order.number(), new BigDecimal("70"), "CDEK-EXTERNAL");
        Estimate actual = new Estimate(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("60"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("1000"));
        assertThat(accounting.actual(111, order.number(), actual, BigDecimal.ZERO).costsAtSavedRates().delivery()).isZero();
        assertThat(accounting.report(111)).contains("70,00 ₽");
    }

    @Test
    void allThreePickupPointsAreFreeAndChangingFulfillmentWithdrawsPublishedOffer() {
        PrintOrder order = draft(222);
        Quote initial = estimate(222, order, Fixtures.estimate());
        click(222, "pickupList:" + order.id());
        for (ReceivingPoints.Point point : ReceivingPoints.ALL) {
            assertThat(jdbc.sql("SELECT payload FROM outbox").query(String.class).list())
                    .anyMatch(p -> p.contains(point.mapUrl()) && p.contains("ссылка на адрес"));
            service.selfPickup(222, order.id(), point.key());
            DeliveryDetails details = delivery.get(order.id());
            assertThat(details.address()).isEqualTo(point.address());
            assertThat(details.fullName()).isNull();
            assertThat(details.externalPrice()).isZero();
        }
        service.approve(111, order.number(), "Самовывоз согласован");
        assertThatThrownBy(() -> service.priceDelivery(111, order.id(), initial.id(), BigDecimal.TEN))
                .isInstanceOf(BusinessException.class);
        service.selfPickup(222, order.id(), "baumanskaya");
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        assertThat(orders.get(order.id()).quoteId()).isNotEqualTo(initial.id());
        assertThatThrownBy(() -> service.accept(222, initial.id())).isInstanceOf(BusinessException.class);
    }

    @Test
    void customColorApprovalUsesItsRealSpoolCostAndIsRestrictedToThatOrder() {
        PrintOrder order = draft(222);
        click(222, "customColor:" + order.id());
        message(222, 222, Map.of("photo", List.of(Map.of("file_id", "wrong", "width", 100, "height", 100))));
        assertThat(count("color_requests")).isZero();
        send(222, "Бирюзовый #00aabb");
        UUID id = jdbc.sql("SELECT id FROM color_requests").query(UUID.class).single();
        assertThat(requests.get(id).hexCode()).isEqualTo("#00AABB");
        assertThat(orders.get(order.id()).colorId()).isNull();
        click(111, -12345, "cc:accept:" + id);
        group(111, Map.of("text", "Бирюзовый"));
        group(111, Map.of("text", "900"));
        click(111, -12345, "cc:kg");
        click(111, -12345, "cc:save");
        ColorRequest approved = requests.get(id);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(colors.get(approved.approvedColorId()).available()).isFalse();
        assertThat(orders.get(order.id()).materialId()).isEqualTo("pla-black");
        assertThat(messages(222)).contains("увеличение срока", "купить пластик");
        PrintOrder other = draft(333);
        assertThatThrownBy(() -> service.configure(333, other.id(), "color", approved.approvedColorId().toString()))
                .isInstanceOf(BusinessException.class);
        assertThat(estimate(222, order, grams("10", "60")).snapshot().publicPrice().regularPrintPrice()).isEqualByComparingTo("90");
        assertThatThrownBy(() -> custom.reject(111, id)).isInstanceOf(BusinessException.class);
    }

    @Test
    void customColorRejectionAndCatalogSelectionInvalidateOldOperatorButtons() {
        PrintOrder order = draft(222);
        custom.request(222, order.id(), "Красный #ff0000");
        UUID first = jdbc.sql("SELECT id FROM color_requests").query(UUID.class).single();
        custom.reject(111, first);
        assertThat(messages(222)).contains("Выбери другой из доступных");
        custom.request(222, order.id(), "Синий #0000ff");
        UUID second = jdbc.sql("SELECT id FROM color_requests WHERE status = 'PENDING'").query(UUID.class).single();
        service.configure(222, order.id(), "color", BLACK.toString());
        assertThat(requests.get(second).status()).isEqualTo("SUPERSEDED");
        assertThatThrownBy(() -> custom.approve(111, second, "Синий", new BigDecimal("800"), 1000))
                .isInstanceOf(BusinessException.class);
        assertThat(orders.get(order.id()).colorId()).isEqualTo(BLACK);
    }

    @Test
    void leftMenuReplacesReplyKeyboardAndUnexpectedMediaNeverCreatesAnOrder() {
        new TelegramMenu(outbox, clock).configure();
        assertThat(jdbc.sql("SELECT payload FROM outbox WHERE method = 'setMyCommands'").query(String.class).list())
                .anyMatch(p -> p.contains("Новый заказ") && p.contains("orders") && p.contains("Поддержка"));
        assertThat(count("orders")).isZero();
        send(222, "/start");
        send(222, "Случайное сообщение");
        message(222, 222, Map.of("voice", Map.of("file_id", "voice")));
        message(222, 222, Map.of("video_note", Map.of("file_id", "round")));
        assertThat(count("orders")).isZero();
        assertThat(messages(222)).contains("меню слева");
        assertThat(jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendMessage'").query(String.class).list())
                .anyMatch(p -> p.contains("remove_keyboard") && !p.contains("is_persistent"));
    }

    private Quote estimate(long user, PrintOrder order, Estimate estimate) {
        service.submit(user, order.id());
        return service.estimate(111, order.number(), estimate);
    }
    private Estimate grams(String grams, String minutes) {
        return new Estimate(new BigDecimal(grams), BigDecimal.ZERO, new BigDecimal(minutes),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
