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
    @Autowired DeliveryDao deliveries;
    @Autowired ModelingService modeling;
    @Autowired DialogDao dialogs;
    @Autowired ru.printlab.catalog.ColorService colors;
    @Autowired AccountingService accounting;
    @Autowired UpdateProcessor processor;
    @Autowired OutboxWorker sender;
    @Autowired SlicingDao jobs;
    @Autowired SlicingTransactions slicing;
    @Autowired PromotionDao reservations;
    @Autowired JdbcClient jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired Json json;
    @Autowired MutableClock clock;
    @MockitoBean TelegramGateway telegram;
    private long updateId;

    @BeforeEach
    void prepare() {
        for (String table : List.of("customer_intake", "order_messages", "bot_dialogs", "modeling_receipts", "delivery_details", "outbox", "order_actuals", "order_events", "payments", "promotion_reservations",
                "slicing_jobs", "quotes", "modeling_photos", "orders", "model_files", "customers")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM filament_colors WHERE id NOT IN ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000003')").update();
        jdbc.sql("UPDATE filament_colors SET available = TRUE, deleted = FALSE").update();
        jdbc.sql("UPDATE promo_codes SET created_at = '2020-01-01T00:00:00Z', starts_at = '2020-01-01T00:00:00Z', expires_at = '2030-01-01T00:00:00Z', enabled = TRUE, deleted = FALSE, used_users = 0, used_orders = 0").update();
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
        send(222, BotText.get("button.model"));
        JsonNode photo = update(222, Map.of("photo", List.of(
                Map.of("file_id", "small-photo", "width", 100, "height", 100),
                Map.of("file_id", "large-photo", "width", 1000, "height", 1000)), "caption", "Корпус 30×40 мм"));
        processor.process(photo);
        processor.process(photo);
        assertThat(count("modeling_photos")).isEqualTo(1);
        processor.process(update(222, Map.of("photo", List.of(Map.of("file_id", "second-angle", "width", 200, "height", 200)))));
        assertThat(count("modeling_photos")).isEqualTo(2);
        assertThat(customerMessages(222)).doesNotContain("Фото получили!");
        clock.set(clock.instant().plusSeconds(4));
        modeling.receipts();
        modeling.receipts();
        assertThat(customerMessages(222).split("Фото получили!", -1)).hasSize(2);
        assertThat(count("slicing_jobs")).isZero();
        assertThat(jdbc.sql("SELECT telegram_file_id FROM modeling_photos WHERE telegram_file_id = 'large-photo'").query(String.class).single()).isEqualTo("large-photo");
        PrintOrder request = orders.list(222, false).getFirst();
        assertThat(request.status()).isEqualTo(OrderStatus.MODELING);
        send(222, "Высота 10 мм, нужен PLA");
        send(111, "/model_reply " + request.number() + " Стоимость моделирования согласуем отдельно");
        assertThat(customerMessages(222)).contains("Стоимость моделирования согласуем отдельно");
        jdbc.sql("UPDATE operator_chat SET telegram_chat_id = -12345").update();
        group(111, -999, Map.of("text", "/model_reply " + request.number() + " Чужая группа"));
        assertThat(customerMessages(222)).doesNotContain("Чужая группа");
        group(333, -12345, Map.of("text", "/model_reply " + request.number() + " Посторонний участник"));
        assertThat(customerMessages(222)).doesNotContain("Посторонний участник");
        group(111, -12345, Map.of("text", "/model_reply " + request.number() + " Ответ из группы"));
        assertThat(customerMessages(222)).contains("Ответ из группы");
        assertThat(customerMessages(-12345)).contains("Сообщение отправлено клиенту");
        groupClick(111, -12345, "op:open:" + request.id());
        // Reply routing uses IDs returned by Telegram, never text parsed from a forwarded card.
        when(telegram.deliver(anyString(), anyString())).thenAnswer(invocation -> {
            JsonNode payload = json.read(invocation.getArgument(1), JsonNode.class);
            return json.read(json.write(Map.of("message_id", 10000L + invocation.getArgument(1).hashCode() % 100000L + 100000L,
                    "chat", Map.of("id", payload.path("chat_id").asLong()))), JsonNode.class);
        });
        while (sender.deliverOne()) { }
        long card = jdbc.sql("SELECT message_id FROM order_messages WHERE chat_id = -12345 AND order_id = ? LIMIT 1")
                .param(request.id()).query(Long.class).single();
        group(111, -12345, Map.of("text", "Ответ обычным reply", "reply_to_message", Map.of("message_id", card)));
        assertThat(customerMessages(222)).contains("Ответ обычным reply");
        group(111, -12345, Map.of("text", "Неизвестная карточка", "reply_to_message", Map.of("message_id", 1)));
        assertThat(customerMessages(222)).doesNotContain("Неизвестная карточка");
        groupClick(111, -12345, "op:reply:" + request.id());
        group(111, -12345, Map.of("text", "Ответ через кнопку"));
        assertThat(customerMessages(222)).contains("Ответ через кнопку");
        groupClick(111, -12345, "op:stl:" + request.id());
        processor.process(update(333, Map.of("document", Map.of("file_id", "stl-file", "file_name", "part.stl"),
                "caption", "/model_stl " + request.number())));
        assertThat(orders.get(request.id()).status()).isEqualTo(OrderStatus.MODELING);
        group(111, -12345, Map.of("text", "/model_stl " + request.number()));
        groupClick(111, -12345, "op:stl:" + request.id());
        assertThat(customerMessages(-12345)).contains("Отправьте готовый STL как файл");
        group(111, -12345, Map.of("document", Map.of("file_id", "stl-file", "file_name", "part.stl", "file_size", 134),
                "caption", "/model_stl " + request.number()));
        assertThat(orders.get(request.id()).status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(customerMessages(-12345)).contains("Модель передана клиенту");
        PrintOrder draft = orders.draft(222);
        assertThat(draft.customerId()).isEqualTo(orders.customerId(222));
        assertThat(draft.customerId()).isInstanceOf(UUID.class);
        click(222, "pick:" + draft.number() + ":00000000-0000-4000-8000-000000000001");
        click(222, "input:" + draft.id() + ":layer");
        send(222, "0,07");
        assertThat(orders.get(draft.id()).layerHeightMm()).isEqualByComparingTo("0.20");
        send(222, "0,16");
        assertThat(orders.get(draft.id()).layerHeightMm()).isEqualByComparingTo("0.16");
        click(222, "input:" + draft.id() + ":infill");
        send(222, "3");
        assertThat(orders.get(draft.id()).infill()).isEqualTo(15);
        assertThat(customerMessages(222)).contains("ниже 5%");
        click(222, "lowkeep:" + draft.id());
        assertThat(orders.get(draft.id()).infill()).isEqualTo(3);
        click(222, "input:" + draft.id() + ":infill");
        send(222, "101");
        assertThat(orders.get(draft.id()).infill()).isEqualTo(3);
        send(222, "100");
        assertThat(orders.get(draft.id()).infill()).isEqualTo(100);
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
        assertThat(orders.get(priced.id()).status()).isEqualTo(OrderStatus.QUOTE_READY);
        click(222, "ship:" + priced.id() + ":PICKUP");
        send(222, "Иванов Иван Иванович");
        send(222, "+7 (999) 123-45-67");
        send(222, "Москва, ПВЗ СДЭК, Тестовая 1");
        assertThat(deliveries.get(priced.id()).fullName()).isEqualTo("Иванов Иван Иванович");
        assertThat(customerMessages(-12345)).contains("Москва, ПВЗ СДЭК", "+79991234567");
        groupClick(111, -12345, "op:delivery:" + priced.id());
        group(111, -12345, Map.of("text", "350,50"));
        UUID finalId = orders.get(priced.id()).quoteId();
        assertThat(orders.quote(finalId).snapshot().publicPrice().total()).isEqualByComparingTo("900.50");
        assertThat(orders.quote(finalId).snapshot().publicPrice().regularPrintPrice()).isEqualByComparingTo("550.00");
        assertThat(orders.quote(finalId).snapshot().costs().delivery()).isEqualByComparingTo("350.50");
        click(222, "accept:" + priced.quoteId());
        assertThat(orders.get(priced.id()).status()).isEqualTo(OrderStatus.QUOTE_READY);
        click(222, "accept:" + finalId);
        groupClick(111, -12345, "op:paid:" + priced.id());
        assertThat(count("payments")).isZero();
        groupClick(111, -12345, "op:payConfirm:" + priced.id());
        group(111, -12345, Map.of("text", "transfer-1"));
        assertThat(orders.get(priced.id()).status()).isEqualTo(OrderStatus.PAID);
        groupClick(111, -12345, "op:to_PRINTING:" + priced.id());
        groupClick(111, -12345, "op:to_READY:" + priced.id());
        groupClick(111, -12345, "op:to_SHIPPED:" + priced.id());
        assertThat(orders.get(priced.id()).status()).isEqualTo(OrderStatus.SHIPPED);
        groupClick(111, -12345, "op:to_COMPLETED:" + priced.id());
        send(111, "/actual " + priced.number() + " 80 220 10 10 0 0 5");
        assertThat(orders.get(priced.id()).status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("order_actuals")).isEqualTo(1);
        assertThat(accounting.report(111)).contains("card01", "900,50 ₽");
        colors.delete(111, UUID.fromString("00000000-0000-4000-8000-000000000001"));
        assertThat(service.summary(orders.get(priced.id()))).contains("Чёрный");
        assertThat(orders.quote(finalId).snapshot().color().name()).isEqualTo("Чёрный");
        assertThat(customerMessages(222)).doesNotContain("Маржинальный", "себестоимость", "example-v1", "287.29");
        for (String value : jdbc.sql("SELECT payload FROM outbox").query(String.class).list()) {
            JsonNode payload = json.read(value, JsonNode.class);
            assertThat(payload.path("text").asText().length()).isLessThanOrEqualTo(4096);
            for (JsonNode row : payload.path("reply_markup").path("inline_keyboard")) {
                for (JsonNode key : row) {
                    assertThat(key.path("callback_data").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                            .isLessThanOrEqualTo(64);
                }
            }
        }
    }

    @Test
    void lowContributionRequiresExplicitAdminApprovalAndPaymentConsumesPromotionOnlyOnce() {
        PrintOrder order = submitted(222, "FIRST50");
        Quote quote = service.estimate(111, order.number(), Fixtures.estimate());
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        assertThat(jdbc.sql("SELECT redeemed FROM promotion_reservations").query(Boolean.class).single()).isFalse();
        assertThatThrownBy(() -> service.accept(222, quote.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.approve(222, order.number(), "Разрешаю тестовую скидку"))
                .isInstanceOf(BusinessException.class);
        service.approve(111, order.number(), "Проверка спроса в рамках рекламного бюджета");
        assertThat(orders.quote(quote.id()).approvedBy()).isEqualTo(orders.customerId(111));
        service.accept(222, finalQuote(222, quote.id()));
        service.paid(111, order.number(), quote.snapshot().publicPrice().total(), "transfer-promo");
        service.paid(111, order.number(), quote.snapshot().publicPrice().total(), "transfer-promo");
        assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT redeemed FROM promotion_reservations").query(Boolean.class).single()).isTrue();
        PrintOrder second = submitted(222, "FIRST50");
        Quote repeat = service.estimate(111, second.number(), Fixtures.estimate());
        assertThat(repeat.snapshot().publicPrice().discount()).isZero();
    }

    @Test
    void duplicateUpdateDoesNotCreateAnotherDraftOrNotificationAndInvalidInputDoesNotBlockCursor() {
        send(222, "/start");
        String home = jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendMessage' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        JsonNode keyboard = json.read(home, JsonNode.class).path("reply_markup");
        assertThat(keyboard.path("is_persistent").asBoolean()).isTrue();
        assertThat(keyboard.path("keyboard").size()).isEqualTo(2);
        assertThat(home).contains("Новый заказ", "Мои заказы", "Помощь");
        assertThat(keyboard.toString()).doesNotContain(BotText.get("button.print"), BotText.get("button.model"));
        assertThat(json.read(home, JsonNode.class).path("text").asText()).isEqualTo("""
                Привет!
                Мы команда PrintLab и занимаемся 3D-печатью.

                Для печати нужен STL. Это файл с объёмной моделью будущей детали.

                Модель уже есть? Присылай STL, рассчитаем стоимость печати.

                Модели пока нет? Присылай фото или эскиз, желательно с точными размерами и требованиями. Обсудим детали и подготовим модель для печати.""");
        send(222, BotText.get("button.print"));
        assertThat(count("orders")).isZero();
        click(222, "consent:print");
        assertThat(orders.consented(222)).isTrue();
        assertThat(customerMessages(222)).contains(BotText.get("upload.stl"));
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
        assertThat(count("modeling_photos")).isZero();
        assertThat(orders.list(222, false).getFirst().status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(customerMessages(222)).contains("Выбрана печать по STL");
        send(222, BotText.get("button.model"));
        processor.process(update(222, Map.of("document", Map.of("file_id", "reference-png", "file_name", "reference.PNG", "file_size", 100))));
        PrintOrder request = orders.list(222, false).getFirst();
        assertThat(request.status()).isEqualTo(OrderStatus.MODELING);
        assertThat(orders.draft(222).quantity()).isEqualTo(3);
        send(222, "/cancel " + request.number());
        assertThat(orders.get(request.id()).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void rejectsUnauthorizedAdminCallsAndOtherCustomersButtons() {
        PrintOrder order = submitted(222, "");
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
        groupClick(555, -12345, "cat:new");
        assertThat(dialogs.get(555, -12345)).isNull();
        groupClick(444, -12345, "cat:new");
        group(111, -12345, Map.of("text", "Чужой ввод не меняет мастер"));
        assertThat(dialogs.get(444, -12345).step()).isEqualTo("name");
        group(444, -12345, Map.of("text", "Красный"));
        for (int field = 0; field < 4; field++) {
            groupClick(444, -12345, "cat:skip");
        }
        var red = colors.page(false, 0).stream().filter(color -> color.name().equals("Красный")).findFirst().orElseThrow();
        assertThat(red.manufacturer()).isNull();
        for (String name : List.of("Зелёный", "Жёлтый", "Синий", "Серый")) {
            colors.save(444, new ru.printlab.catalog.FilamentColor(null, name, null, null, null, null, true, false));
        }
        groupClick(444, -12345, "cat:toggle:" + red.id());
        assertThat(colors.get(red.id()).available()).isFalse();
        click(333, "agree");
        PrintOrder colorDraft = service.attach(333, "stl-file", "colors.stl", Fixtures.stl());
        click(333, "colors:" + colorDraft.id() + ":0");
        JsonNode page = json.read(jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendMessage' ORDER BY id DESC LIMIT 1")
                .query(String.class).single(), JsonNode.class);
        assertThat(page.path("reply_markup").path("inline_keyboard").size()).isEqualTo(7);
        assertThat(page.toString()).contains("Следующая").doesNotContain("Красный");
        click(333, "colors:" + colorDraft.id() + ":1");
        assertThat(jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendMessage' ORDER BY id DESC LIMIT 1").query(String.class).single()).contains("Предыдущая");
        groupClick(444, -12345, "cat:deleteYes:" + red.id());
        assertThatThrownBy(() -> colors.get(red.id())).isInstanceOf(BusinessException.class);
        groupClick(555, -12345, "op:reply:" + order.id());
        assertThat(dialogs.get(555, -12345)).isNull();
        groupClick(444, -999, "op:reply:" + order.id());
        assertThat(dialogs.get(444, -999)).isNull();
        send(333, BotText.get("button.model"));
        processor.process(update(333, Map.of("photo", List.of(Map.of("file_id", "group-photo", "width", 100, "height", 100)))));
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE method = 'sendPhoto'").query(String.class).single();
        assertThat(json.read(payload, JsonNode.class).path("chat_id").asLong()).isEqualTo(-12345);
    }

    @Test
    void oldQuoteAndExpiredPaymentAreRejectedAndReservationIsReleased() {
        PrintOrder order = submitted(222, "FIRST50");
        Quote old = service.estimate(111, order.number(), Fixtures.estimate());
        Quote current = service.estimate(111, order.number(), Fixtures.estimate());
        assertThatThrownBy(() -> service.accept(222, old.id())).isInstanceOf(BusinessException.class);
        service.approve(111, order.number(), "Рекламный бюджет");
        service.accept(222, finalQuote(222, current.id()));
        clock.set(current.expiresAt());
        assertThatThrownBy(() -> service.paid(111, order.number(), current.snapshot().publicPrice().total(), "late-transfer"))
                .isInstanceOf(BusinessException.class);
        service.expire();
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(count("promotion_reservations")).isZero();
    }

    @Test
    void refusesWrongAmountAndReusedPaymentReferenceAndPrepaidPrinting() {
        PrintOrder first = submitted(222, "");
        Quote quote = service.estimate(111, first.number(), Fixtures.estimate());
        assertThatThrownBy(() -> service.production(111, first.number(), OrderStatus.PRINTING)).isInstanceOf(BusinessException.class);
        service.accept(222, finalQuote(222, quote.id()));
        assertThatThrownBy(() -> service.paid(111, first.number(), new BigDecimal("0.01"), "transfer-A"))
                .isInstanceOf(BusinessException.class);
        service.paid(111, first.number(), quote.snapshot().publicPrice().total(), "transfer-A");
        PrintOrder second = submitted(333, "");
        Quote other = service.estimate(111, second.number(), Fixtures.estimate());
        service.accept(333, finalQuote(333, other.id()));
        assertThatThrownBy(() -> service.paid(111, second.number(), other.snapshot().publicPrice().total(), "transfer-A"))
                .isInstanceOf(BusinessException.class);
        service.refund(111, first.number(), "refund-A");
        service.refund(111, first.number(), "refund-A");
        assertThat(orders.get(first.id()).status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(orders.paidCount(first.customerId())).isEqualTo(1);
    }

    @Test
    void twoReservedOffersPreventThirdDiscountAndCancellationReleasesSlot() {
        PrintOrder first = null;
        for (int index = 0; index < 2; index++) {
            PrintOrder order = submitted(1000 + index, "FIRST50");
            service.estimate(111, order.number(), Fixtures.estimate());
            if (first == null) {
                first = order;
            }
        }
        PrintOrder extra = submitted(2000, "FIRST50");
        Quote blocked = service.estimate(111, extra.number(), Fixtures.estimate());
        assertThat(blocked.snapshot().publicPrice().discount()).isZero();
        service.cancel(1000, first.id());
        Quote released = service.estimate(111, extra.number(), Fixtures.estimate());
        assertThat(released.snapshot().publicPrice().discount()).isEqualByComparingTo("275.00");

        PrintOrder waiting = submitted(3000, "FIRST30");
        Quote tooEarly = service.estimate(111, waiting.number(), Fixtures.estimate());
        assertThat(tooEarly.snapshot().publicPrice().discount()).isZero();
        PrintOrder second = orders.list(1001, false).getFirst();
        int payments = 0;
        for (PrintOrder paid : List.of(second, extra)) {
            PrintOrder current = orders.get(paid.id());
            Quote quote = orders.quote(current.quoteId());
            service.approve(111, current.number(), "Проверка двух этапов акции");
            long customer = orders.telegramId(current.customerId());
            service.accept(customer, finalQuote(customer, quote.id()));
            service.paid(111, current.number(), quote.snapshot().publicPrice().total(), "promo-" + current.number());
            payments++;
            if (payments == 1) {
                assertThat(service.estimate(111, waiting.number(), Fixtures.estimate()).snapshot().publicPrice().discount()).isZero();
            }
        }
        assertThat(reservations.paidSlots("FIRST50")).isEqualTo(2);
        Quote available = service.estimate(111, waiting.number(), Fixtures.estimate());
        assertThat(available.snapshot().publicPrice().discount()).isEqualByComparingTo("165.00");
        assertThat(available.snapshot().promotion().code()).isEqualTo("FIRST30");
        PrintOrder usedBoth = submitted(1001, "FIRST30");
        assertThat(service.estimate(111, usedBoth.number(), Fixtures.estimate()).snapshot().publicPrice().discount()).isZero();
        for (int index = 0; index < 7; index++) {
            PrintOrder order = submitted(4000 + index, "FIRST30");
            assertThat(service.estimate(111, order.number(), Fixtures.estimate()).snapshot().publicPrice().discount())
                    .isEqualByComparingTo("165.00");
        }
        PrintOrder ninth = submitted(5000, "FIRST30");
        assertThat(service.estimate(111, ninth.number(), Fixtures.estimate()).snapshot().publicPrice().discount()).isZero();
        service.cancel(3000, waiting.id());
        assertThat(service.estimate(111, ninth.number(), Fixtures.estimate()).snapshot().publicPrice().discount())
                .isEqualByComparingTo("165.00");
    }

    @Test
    void concurrentLastPromotionSlotCannotBeReservedTwice() throws Exception {
        for (int index = 0; index < 1; index++) {
            PrintOrder order = submitted(1000 + index, "FIRST50");
            service.estimate(111, order.number(), Fixtures.estimate());
        }
        PrintOrder left = submitted(2001, "FIRST50");
        PrintOrder right = submitted(2002, "FIRST50");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Quote> first = executor.submit(() -> { start.await(); return service.estimate(111, left.number(), Fixtures.estimate()); });
            Future<Quote> second = executor.submit(() -> { start.await(); return service.estimate(111, right.number(), Fixtures.estimate()); });
            start.countDown();
            BigDecimal discounts = first.get().snapshot().publicPrice().discount().add(second.get().snapshot().publicPrice().discount());
            assertThat(discounts).isEqualByComparingTo("275.00");
        }
        assertThat(count("promotion_reservations")).isEqualTo(2);
    }

    @Test
    void outboxRetriesTemporaryFailureWithoutLosingMessages() {
        when(telegram.deliver(anyString(), anyString())).thenThrow(new TelegramFailure(false, 2)).thenReturn(null);
        sender.deliverOne();
        assertThat(jdbc.sql("SELECT attempts FROM outbox ORDER BY id LIMIT 1").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT delivered FROM outbox ORDER BY id LIMIT 1").query(Boolean.class).single()).isFalse();
        clock.set(clock.instant().plusSeconds(20));
        sender.deliverOne();
        assertThat(jdbc.sql("SELECT delivered FROM outbox ORDER BY id LIMIT 1").query(Boolean.class).single()).isTrue();
    }

    @Test
    void automaticSlicingCompletionCreatesQuoteAndCancellationIgnoresLateResults() {
        PrintOrder order = submitted(222, "");
        jobs.enqueue(order.id(), clock.instant());
        SliceJob job = slicing.claim();
        SliceResult result = new SliceResult(Fixtures.estimate(), "test-engine, measured fixture, not real slicer");
        slicing.complete(job, result);
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.QUOTE_READY);
        assertThat(count("quotes")).isEqualTo(1);
        PrintOrder retry = submitted(444, "");
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
        PrintOrder cancelled = submitted(333, "");
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
        PrintOrder order = submitted(222, "FIRST50");
        Quote quote = service.estimate(111, order.number(), Fixtures.estimate());
        assertThat(quote.snapshot().publicPrice().discount()).isZero();
        assertThat(quote.snapshot().publicPrice().promotionNote()).contains("не действует");
        assertThat(count("promotion_reservations")).isZero();
    }

    @Test
    void uncappedCampaignReservesTheActualDiscount() {
        PrintOrder order = submitted(222, "FIRST50");
        Estimate large = new Estimate(new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("600"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Quote quote = service.estimate(111, order.number(), large);
        assertThat(quote.snapshot().publicPrice().discount()).isGreaterThan(new BigDecimal("600"));
        assertThat(jdbc.sql("SELECT discount FROM promotion_reservations").query(BigDecimal.class).single())
                .isEqualByComparingTo(quote.snapshot().publicPrice().discount());
    }

    @Test
    void oneCustomerCannotReserveFirstPromotionInTwoOrders() {
        PrintOrder first = submitted(222, "FIRST50");
        service.estimate(111, first.number(), Fixtures.estimate());
        PrintOrder second = submitted(222, "FIRST50");
        Quote quote = service.estimate(111, second.number(), Fixtures.estimate());
        assertThat(quote.snapshot().publicPrice().discount()).isZero();
        assertThat(count("promotion_reservations")).isEqualTo(1);
    }

    @Test
    void recalculationPreservesPreviousSnapshot() {
        PrintOrder order = submitted(222, "");
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
        PrintOrder order = submitted(222, "");
        Estimate invalid = new Estimate(new BigDecimal("75"), BigDecimal.ZERO, new BigDecimal("210"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 2);
        assertThatThrownBy(() -> service.estimate(111, order.number(), invalid))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Число пластин");
        assertThat(count("quotes")).isZero();
    }

    @Test
    void upgradesExistingOrdersWithoutLosingStoredParameters() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("1.0.0").load().migrate();
        try {
            UUID customer = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            jdbc.sql("INSERT INTO " + schema + ".customers(id, telegram_id, display_name, created_at) VALUES (?, 123, 'Migration fixture', CURRENT_TIMESTAMP)")
                    .param(customer).update();
            jdbc.sql("INSERT INTO " + schema + ".orders(id, customer_id, status, print_profile, infill, quantity, promo_code, created_at) VALUES (?, ?, 'DRAFT', 'FINE', 60, 5, 'FIRST50', CURRENT_TIMESTAMP)")
                    .param(order).param(customer).update();
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .load().migrate();
            Map<String, Object> saved = jdbc.sql("SELECT infill, quantity, promo_code, layer_height_mm FROM " + schema + ".orders WHERE id = ?")
                    .param(order).query().singleRow();
            assertThat(((Number) saved.get("infill")).intValue()).isEqualTo(60);
            assertThat(((Number) saved.get("quantity")).intValue()).isEqualTo(5);
            assertThat(saved.get("promo_code")).isEqualTo("FIRST50");
            assertThat((BigDecimal) saved.get("layer_height_mm")).isEqualByComparingTo("0.12");
            jdbc.sql("UPDATE " + schema + ".orders SET infill = 1 WHERE id = ?").param(order).update();
            jdbc.sql("UPDATE " + schema + ".orders SET infill = 100 WHERE id = ?").param(order).update();
            assertThatThrownBy(() -> jdbc.sql("UPDATE " + schema + ".orders SET infill = 101 WHERE id = ?").param(order).update())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    private UUID finalQuote(long user, UUID quoteId) {
        Quote original = orders.quote(quoteId);
        PrintOrder order = orders.get(original.orderId());
        DeliveryDetails existing = deliveries.get(order.id());
        if (existing != null && quoteId.equals(existing.pricedQuoteId())) {
            return quoteId;
        }
        service.delivery(user, order.id(), "PICKUP", "Иван Иванов Иванович", "+79991234567", "Москва, ПВЗ СДЭК, Тестовая 1");
        Quote priced = service.priceDelivery(111, order.id(), quoteId, BigDecimal.ZERO);
        if (orders.get(order.id()).status() == OrderStatus.REVIEW_REQUIRED) {
            service.approve(111, order.number(), "Согласована тестовая доставка");
        }
        return priced.id();
    }

    private PrintOrder submitted(long user, String promo) {
        send(user, "/start card01");
        click(user, "agree");
        PrintOrder order = service.attach(user, "stl-file", "part.stl", Fixtures.stl());
        service.configure(user, order.id(), "color", "00000000-0000-4000-8000-000000000001");
        if (!promo.isBlank()) {
            orders.promo(order.id(), promo);
        }
        service.submit(user, order.id());
        return orders.get(order.id());
    }

    private void send(long user, String text) {
        processor.process(update(user, Map.of("text", text)));
    }

    private void group(long user, long chat, Map<String, Object> body) {
        Map<String, Object> message = new java.util.LinkedHashMap<>(body);
        message.put("chat", Map.of("id", chat, "type", "supergroup"));
        message.put("from", Map.of("id", user, "first_name", "Оператор"));
        processor.process(json.read(json.write(Map.of("update_id", updateId++, "message", message)), JsonNode.class));
    }

    private void groupClick(long user, long chat, String callback) {
        Map<String, Object> query = Map.of("id", "callback-" + updateId, "data", callback,
                "from", Map.of("id", user), "message", Map.of("chat", Map.of("id", chat, "type", "supergroup")));
        processor.process(json.read(json.write(Map.of("update_id", updateId++, "callback_query", query)), JsonNode.class));
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
