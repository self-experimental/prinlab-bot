package ru.printlab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.order.*;
import ru.printlab.pricing.Estimate;
import ru.printlab.promotion.PromotionDao;
import ru.printlab.slicing.*;
import ru.printlab.telegram.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "printlab.operations.admin-usernames=oilbanana,muslimitsuhide")
class WorkflowTest {
    @Autowired OrderService service;
    @Autowired OrderDao orders;
    @Autowired AccountingService accounting;
    @Autowired UpdateProcessor processor;
    @Autowired OutboxWorker sender;
    @Autowired SlicingDao jobs;
    @Autowired SlicingTransactions slicing;
    @Autowired PromotionDao reservations;
    @Autowired JdbcClient jdbc;
    @Autowired Json json;
    @Autowired MutableClock clock;
    @MockitoBean TelegramGateway telegram;
    private long updateId;

    @BeforeEach
    void prepare() {
        for (String table : List.of("outbox", "order_actuals", "order_events", "payments", "promotion_reservations",
                "slicing_jobs", "quotes", "modeling_photos", "orders", "model_files", "customers")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE bot_cursor SET next_update = 0").update();
        jdbc.sql("DELETE FROM operator_accounts").update();
        jdbc.sql("UPDATE operator_chat SET telegram_chat_id = NULL").update();
        clock.set(Instant.parse("2026-10-23T12:00:00Z"));
        updateId = 1;
        send(111, "/start");
        when(telegram.download("stl-file")).thenReturn(Fixtures.stl());
    }

    @Test
    void fullCustomerBotFlowThroughPaymentProductionAndActualCosts() {
        send(222, "/start card01");
        click(222, "agree");
        send(222, "/model");
        JsonNode photo = update(222, Map.of("photo", List.of(
                Map.of("file_id", "small-photo", "width", 100, "height", 100),
                Map.of("file_id", "large-photo", "width", 1000, "height", 1000)), "caption", "Корпус 30×40 мм"));
        processor.process(photo);
        processor.process(photo);
        assertThat(count("modeling_photos")).isEqualTo(1);
        assertThat(count("slicing_jobs")).isZero();
        assertThat(jdbc.sql("SELECT telegram_file_id FROM modeling_photos").query(String.class).single()).isEqualTo("large-photo");
        PrintOrder request = orders.list(222, false).getFirst();
        assertThat(request.status()).isEqualTo(OrderStatus.MODELING);
        send(222, "Высота 10 мм, нужен PLA");
        send(111, "/model_reply " + request.number() + " Стоимость моделирования согласуем отдельно");
        assertThat(customerMessages(222)).contains("Стоимость моделирования согласуем отдельно");
        processor.process(update(333, Map.of("document", Map.of("file_id", "stl-file", "file_name", "part.stl"),
                "caption", "/model_stl " + request.number())));
        assertThat(orders.get(request.id()).status()).isEqualTo(OrderStatus.MODELING);
        processor.process(update(111, Map.of("document", Map.of("file_id", "stl-file", "file_name", "part.stl", "file_size", 134),
                "caption", "/model_stl " + request.number())));
        assertThat(orders.get(request.id()).status()).isEqualTo(OrderStatus.DRAFT);
        PrintOrder draft = orders.draft(222);
        assertThat(draft.customerId()).isEqualTo(orders.customerId(222));
        assertThat(draft.customerId()).isInstanceOf(UUID.class);
        click(222, "material:" + draft.id() + ":pla-black");
        click(222, "profile:" + draft.id() + ":STANDARD");
        click(222, "infill:" + draft.id() + ":15");
        click(222, "quantity:" + draft.id() + ":2");
        click(222, "submit:" + draft.id());
        assertThat(orders.get(draft.id()).status()).isEqualTo(OrderStatus.ESTIMATING);
        assertThat(count("slicing_jobs")).isZero();
        assertThat(customerMessages(222)).contains("Оператор рассчитает печать");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE method = 'sendDocument'")
                .query(Long.class).single()).isEqualTo(2L);
        send(111, "/slice " + draft.number() + " 75 210 10 10 0 0 0");
        assertThat(orders.quote(orders.get(draft.id()).quoteId()).snapshot().estimate().setupMinutes())
                .isEqualByComparingTo("10");
        send(111, "/slice " + draft.number() + " 75 210");
        assertThat(orders.quote(orders.get(draft.id()).quoteId()).snapshot().estimate().plateCount()).isEqualTo(1);
        send(111, "/slice " + draft.number() + " 75 210 2");
        PrintOrder priced = orders.get(draft.id());
        assertThat(priced.status()).isEqualTo(OrderStatus.QUOTE_READY);
        assertThat(orders.quote(priced.quoteId()).snapshot().estimate().setupMinutes()).isZero();
        assertThat(orders.quote(priced.quoteId()).snapshot().estimate().plateCount()).isEqualTo(2);
        click(222, "accept:" + priced.quoteId());
        send(111, "/paid " + priced.number() + " 550.00 transfer-1");
        assertThat(orders.get(priced.id()).status()).isEqualTo(OrderStatus.PAID);
        send(111, "/status " + priced.number() + " PRINTING");
        send(111, "/status " + priced.number() + " QUALITY_CHECK");
        send(111, "/status " + priced.number() + " READY");
        send(111, "/status " + priced.number() + " COMPLETED");
        send(111, "/actual " + priced.number() + " 80 220 10 10 0 0 5");
        assertThat(orders.get(priced.id()).status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("order_actuals")).isEqualTo(1);
        assertThat(accounting.report(111)).contains("card01", "550.00 ₽");
        assertThat(customerMessages(222)).doesNotContain("Маржинальный", "себестоимость", "example-v1", "287.29");
    }

    @Test
    void lowContributionRequiresExplicitAdminApprovalAndPaymentConsumesPromotionOnlyOnce() {
        PrintOrder order = submitted(222, true);
        Quote quote = service.estimate(111, order.number(), Fixtures.estimate());
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        assertThat(jdbc.sql("SELECT redeemed FROM promotion_reservations").query(Boolean.class).single()).isFalse();
        assertThatThrownBy(() -> service.accept(222, quote.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.approve(222, order.number(), "Разрешаю тестовую скидку"))
                .isInstanceOf(BusinessException.class);
        service.approve(111, order.number(), "Проверка спроса в рамках рекламного бюджета");
        assertThat(orders.quote(quote.id()).approvedBy()).isEqualTo(orders.customerId(111));
        service.accept(222, quote.id());
        service.paid(111, order.number(), quote.snapshot().publicPrice().total(), "transfer-promo");
        service.paid(111, order.number(), quote.snapshot().publicPrice().total(), "transfer-promo");
        assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT redeemed FROM promotion_reservations").query(Boolean.class).single()).isTrue();
        PrintOrder second = submitted(222, true);
        Quote repeat = service.estimate(111, second.number(), Fixtures.estimate());
        assertThat(repeat.snapshot().publicPrice().discount()).isZero();
    }

    @Test
    void duplicateUpdateDoesNotCreateAnotherDraftOrNotificationAndInvalidInputDoesNotBlockCursor() {
        send(222, "/start");
        click(222, "agree");
        JsonNode update = update(222, Map.of("text", "/new"));
        processor.process(update);
        long notifications = count("outbox");
        processor.process(update);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(notifications);
        send(222, "/quantity -1");
        send(222, "/quantity 3");
        assertThat(orders.draft(222).quantity()).isEqualTo(3);
        processor.process(update(222, Map.of("document", Map.of("file_id", "reference-png", "file_name", "reference.PNG", "file_size", 100))));
        PrintOrder request = orders.list(222, false).getFirst();
        assertThat(request.status()).isEqualTo(OrderStatus.MODELING);
        assertThat(orders.draft(222).quantity()).isEqualTo(3);
        send(222, "/cancel " + request.number());
        assertThat(orders.get(request.id()).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void rejectsUnauthorizedAdminCallsAndOtherCustomersButtons() {
        PrintOrder order = submitted(222, false);
        send(333, "/start");
        assertThatThrownBy(() -> service.cancel(333, order.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.estimate(222, order.number(), Fixtures.estimate())).isInstanceOf(BusinessException.class);
        send(222, "/paid " + order.number() + " 1 forged");
        assertThat(count("payments")).isZero();
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.ESTIMATING);
        JsonNode enroll = update(444, Map.of("text", "/start"));
        var enrolled = (tools.jackson.databind.node.ObjectNode) enroll.path("message").path("from");
        enrolled.put("username", "Oilbanana");
        processor.process(enroll);
        assertThat(service.isAdmin(444)).isTrue();
        JsonNode reusedName = update(555, Map.of("text", "/start"));
        ((tools.jackson.databind.node.ObjectNode) reusedName.path("message").path("from")).put("username", "oilbanana");
        processor.process(reusedName);
        assertThat(service.isAdmin(555)).isFalse();
        Map<String, Object> group = Map.of("chat", Map.of("id", -12345, "type", "supergroup"),
                "from", Map.of("id", 444, "username", "oilbanana"), "text", "/operator_chat@printlab3dbot");
        processor.process(json.read(json.write(Map.of("update_id", updateId++, "message", group)), JsonNode.class));
        assertThat(jdbc.sql("SELECT telegram_chat_id FROM operator_chat").query(Long.class).single()).isEqualTo(-12345);
        Map<String, Object> unauthorizedGroup = Map.of("chat", Map.of("id", -999, "type", "group"),
                "from", Map.of("id", 555, "username", "oilbanana"), "text", "/operator_chat");
        processor.process(json.read(json.write(Map.of("update_id", updateId++, "message", unauthorizedGroup)), JsonNode.class));
        assertThat(jdbc.sql("SELECT telegram_chat_id FROM operator_chat").query(Long.class).single()).isEqualTo(-12345);
        click(333, "agree");
        processor.process(update(333, Map.of("photo", List.of(Map.of("file_id", "group-photo", "width", 100, "height", 100)))));
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendPhoto'").query(String.class).single();
        assertThat(json.read(payload, JsonNode.class).path("chat_id").asLong()).isEqualTo(-12345);
    }

    @Test
    void oldQuoteAndExpiredPaymentAreRejectedAndReservationIsReleased() {
        PrintOrder order = submitted(222, true);
        Quote old = service.estimate(111, order.number(), Fixtures.estimate());
        Quote current = service.estimate(111, order.number(), Fixtures.estimate());
        assertThatThrownBy(() -> service.accept(222, old.id())).isInstanceOf(BusinessException.class);
        service.approve(111, order.number(), "Рекламный бюджет");
        service.accept(222, current.id());
        clock.set(current.expiresAt());
        assertThatThrownBy(() -> service.paid(111, order.number(), current.snapshot().publicPrice().total(), "late-transfer"))
                .isInstanceOf(BusinessException.class);
        service.expire();
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(count("promotion_reservations")).isZero();
    }

    @Test
    void refusesWrongAmountAndReusedPaymentReferenceAndPrepaidPrinting() {
        PrintOrder first = submitted(222, false);
        Quote quote = service.estimate(111, first.number(), Fixtures.estimate());
        assertThatThrownBy(() -> service.production(111, first.number(), OrderStatus.PRINTING)).isInstanceOf(BusinessException.class);
        service.accept(222, quote.id());
        assertThatThrownBy(() -> service.paid(111, first.number(), new BigDecimal("0.01"), "transfer-A"))
                .isInstanceOf(BusinessException.class);
        service.paid(111, first.number(), quote.snapshot().publicPrice().total(), "transfer-A");
        PrintOrder second = submitted(333, false);
        Quote other = service.estimate(111, second.number(), Fixtures.estimate());
        service.accept(333, other.id());
        assertThatThrownBy(() -> service.paid(111, second.number(), other.snapshot().publicPrice().total(), "transfer-A"))
                .isInstanceOf(BusinessException.class);
        service.refund(111, first.number(), "refund-A");
        service.refund(111, first.number(), "refund-A");
        assertThat(orders.get(first.id()).status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(orders.paidCount(first.customerId())).isEqualTo(1);
    }

    @Test
    void twentyReservedOffersPreventTwentyFirstDiscountAndCancellationReleasesSlot() {
        PrintOrder first = null;
        for (int index = 0; index < 20; index++) {
            PrintOrder order = submitted(1000 + index, true);
            service.estimate(111, order.number(), Fixtures.estimate());
            if (first == null) {
                first = order;
            }
        }
        PrintOrder extra = submitted(2000, true);
        Quote blocked = service.estimate(111, extra.number(), Fixtures.estimate());
        assertThat(blocked.snapshot().publicPrice().discount()).isZero();
        service.cancel(1000, first.id());
        Quote released = service.estimate(111, extra.number(), Fixtures.estimate());
        assertThat(released.snapshot().publicPrice().discount()).isEqualByComparingTo("275.00");
    }

    @Test
    void concurrentLastPromotionSlotCannotBeReservedTwice() throws Exception {
        for (int index = 0; index < 19; index++) {
            PrintOrder order = submitted(1000 + index, true);
            service.estimate(111, order.number(), Fixtures.estimate());
        }
        PrintOrder left = submitted(2001, true);
        PrintOrder right = submitted(2002, true);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Quote> first = executor.submit(() -> { start.await(); return service.estimate(111, left.number(), Fixtures.estimate()); });
            Future<Quote> second = executor.submit(() -> { start.await(); return service.estimate(111, right.number(), Fixtures.estimate()); });
            start.countDown();
            BigDecimal discounts = first.get().snapshot().publicPrice().discount().add(second.get().snapshot().publicPrice().discount());
            assertThat(discounts).isEqualByComparingTo("275.00");
        }
        assertThat(count("promotion_reservations")).isEqualTo(20);
    }

    @Test
    void outboxRetriesTemporaryFailureWithoutLosingMessages() {
        doThrow(new TelegramFailure(false, 2)).doNothing().when(telegram).deliver(anyString(), anyString());
        sender.deliverOne();
        assertThat(jdbc.sql("SELECT attempts FROM outbox ORDER BY id LIMIT 1").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT delivered FROM outbox ORDER BY id LIMIT 1").query(Boolean.class).single()).isFalse();
        clock.set(clock.instant().plusSeconds(20));
        sender.deliverOne();
        assertThat(jdbc.sql("SELECT delivered FROM outbox ORDER BY id LIMIT 1").query(Boolean.class).single()).isTrue();
    }

    @Test
    void automaticSlicingCompletionCreatesQuoteAndCancellationIgnoresLateResults() {
        PrintOrder order = submitted(222, false);
        jobs.enqueue(order.id(), clock.instant());
        SliceJob job = slicing.claim();
        SliceResult result = new SliceResult(Fixtures.estimate(), "test-engine, measured fixture, not real slicer");
        slicing.complete(job, result);
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.QUOTE_READY);
        assertThat(count("quotes")).isEqualTo(1);
        PrintOrder retry = submitted(444, false);
        jobs.enqueue(retry.id(), clock.instant());
        SliceJob busy = slicing.claim();
        slicing.retry(busy, "Busy");
        assertThat(slicing.claim()).isNull();
        clock.set(clock.instant().plusSeconds(31));
        SliceJob repeated = slicing.claim();
        assertThat(repeated.id()).isEqualTo(busy.id());
        slicing.retry(repeated, "Busy");
        clock.set(clock.instant().plusSeconds(31));
        slicing.retry(slicing.claim(), "Busy");
        assertThat(jdbc.sql("SELECT state FROM slicing_jobs WHERE id = ?").param(busy.id()).query(String.class).single()).isEqualTo("FAILED");
        slicing.complete(job, result);
        assertThat(count("quotes")).isEqualTo(1);
        PrintOrder cancelled = submitted(333, false);
        jobs.enqueue(cancelled.id(), clock.instant());
        SliceJob late = slicing.claim();
        service.cancel(333, cancelled.id());
        slicing.complete(late, result);
        assertThat(orders.get(cancelled.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(count("quotes")).isEqualTo(1);
    }

    @Test
    void expiredCampaignDoesNotApplyDiscount() {
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        PrintOrder order = submitted(222, true);
        Quote quote = service.estimate(111, order.number(), Fixtures.estimate());
        assertThat(quote.snapshot().publicPrice().discount()).isZero();
        assertThat(quote.snapshot().publicPrice().promotionNote()).contains("не действует");
        assertThat(count("promotion_reservations")).isZero();
    }

    @Test
    void budgetIsIndependentOfAvailableSlots() {
        PrintOrder first = submitted(222, true);
        reservations.reserve(first.id(), first.customerId(), "FIRST50", new BigDecimal("11900"), clock.instant().plusSeconds(3600));
        PrintOrder another = submitted(333, true);
        Quote quote = service.estimate(111, another.number(), Fixtures.estimate());
        assertThat(quote.snapshot().publicPrice().discount()).isZero();
        assertThat(count("promotion_reservations")).isEqualTo(1);
    }

    @Test
    void oneCustomerCannotReserveFirstPromotionInTwoOrders() {
        PrintOrder first = submitted(222, true);
        service.estimate(111, first.number(), Fixtures.estimate());
        PrintOrder second = submitted(222, true);
        Quote quote = service.estimate(111, second.number(), Fixtures.estimate());
        assertThat(quote.snapshot().publicPrice().discount()).isZero();
        assertThat(count("promotion_reservations")).isEqualTo(1);
    }

    @Test
    void recalculationPreservesPreviousSnapshot() {
        PrintOrder order = submitted(222, false);
        Quote initial = service.estimate(111, order.number(), Fixtures.estimate());
        Estimate changed = new Estimate(new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("600"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Quote updated = service.estimate(111, order.number(), changed);
        assertThat(updated.id()).isNotEqualTo(initial.id());
        assertThat(orders.quote(initial.id()).snapshot().estimate().grams()).isEqualByComparingTo("75");
        assertThat(orders.quote(initial.id()).snapshot().publicPrice().total()).isEqualByComparingTo("550.00");
        assertThat(orders.quote(updated.id()).snapshot().estimate().grams()).isEqualByComparingTo("1000");
    }

    @Test
    void operatorCannotBillMorePlatesThanItems() {
        PrintOrder order = submitted(222, false);
        Estimate invalid = new Estimate(new BigDecimal("75"), BigDecimal.ZERO, new BigDecimal("210"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 2);
        assertThatThrownBy(() -> service.estimate(111, order.number(), invalid))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Число пластин");
        assertThat(count("quotes")).isZero();
    }

    private PrintOrder submitted(long user, boolean promo) {
        send(user, "/start card01");
        click(user, "agree");
        PrintOrder order = service.attach(user, "stl-file", "part.stl", Fixtures.stl());
        service.configure(user, order.id(), "material", "pla-black");
        if (promo) {
            service.configure(user, order.id(), "promo", "FIRST50");
        }
        service.submit(user, order.id());
        return orders.get(order.id());
    }

    private void send(long user, String text) {
        processor.process(update(user, Map.of("text", text)));
    }

    private JsonNode update(long user, Map<String, Object> body) {
        Map<String, Object> message = new java.util.LinkedHashMap<>(body);
        message.put("chat", Map.of("id", user, "type", "private"));
        message.put("from", Map.of("id", user, "first_name", "Тест"));
        return json.read(json.write(Map.of("update_id", updateId++, "message", message)), JsonNode.class);
    }

    private void click(long user, String callback) {
        Map<String, Object> query = Map.of("id", "callback-" + updateId, "data", callback,
                "from", Map.of("id", user), "message", Map.of("chat", Map.of("id", user, "type", "private")));
        processor.process(json.read(json.write(Map.of("update_id", updateId++, "callback_query", query)), JsonNode.class));
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private String customerMessages(long user) {
        StringBuilder text = new StringBuilder();
        for (String value : jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendMessage'").query(String.class).list()) {
            JsonNode payload = json.read(value, JsonNode.class);
            if (payload.path("chat_id").asLong() == user) {
                text.append(payload.path("text").asText()).append('\n');
            }
        }
        return text.toString();
    }
}
