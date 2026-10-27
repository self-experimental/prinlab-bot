package ru.printlab;

import org.junit.jupiter.api.Test;
import ru.printlab.common.BusinessException;
import ru.printlab.order.PrintOrder;
import ru.printlab.promotion.PromoCode;
import ru.printlab.promotion.PromoType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class PromotionWorkflowTest extends BotTestSupport {
    @Test
    void checkingAndReservingHappensOnlyAfterExplicitApplyAndFailureCanBeSkipped() {
        PrintOrder order = draft(222);
        click(222, "input:" + order.id() + ":promo");
        send(222, "testdate");
        assertThat(count("promotion_reservations")).isZero();
        assertThat(orders.get(order.id()).promoCode()).isEmpty();
        click(222, "promoapply:" + order.id());
        assertThat(orders.get(order.id()).promoCode()).isEqualTo("TESTDATE");
        assertThat(count("promotion_reservations")).isEqualTo(1);
        click(222, "input:" + order.id() + ":promo");
        send(222, "UNKNOWN");
        click(222, "promoapply:" + order.id());
        assertThat(messages(222)).contains("Промокод не найден");
        assertThat(orders.get(order.id()).promoCode()).isEqualTo("TESTDATE");
        click(222, "promo:" + order.id() + ":-");
        assertThat(orders.get(order.id()).promoCode()).isEmpty();
        assertThat(count("promotion_reservations")).isZero();
    }

    @Test
    void allTypesCheckCreationStartAndExpiryBeforeCustomFailureMessages() {
        PrintOrder order = draft(222);
        for (PromoType type : PromoType.values()) {
            PromoCode p = create("DATES_" + type.ordinal(), type, 3, 1, "Своя причина");
            jdbc.sql("UPDATE promo_codes SET created_at = ? WHERE id = ?")
                    .param(java.sql.Timestamp.from(clock.instant().plusSeconds(1))).param(p.id()).update();
            assertThat(service.applyPromotion(222, order.id(), p.code()).note()).contains("ещё не действует");
            jdbc.sql("UPDATE promo_codes SET created_at = ?, starts_at = ?, expires_at = ? WHERE id = ?")
                    .param(java.sql.Timestamp.from(clock.instant().minusSeconds(60)))
                    .param(java.sql.Timestamp.from(clock.instant().minusSeconds(60)))
                    .param(java.sql.Timestamp.from(clock.instant())).param(p.id()).update();
            assertThat(service.applyPromotion(222, order.id(), p.code()).note()).contains("просрочен").doesNotContain("Своя причина");
        }
        assertThat(count("promotion_reservations")).isZero();
        PromoCode future = create("FUTURE", PromoType.DATE, null, 1, "Своя причина");
        clock.set(clock.instant().minusSeconds(1));
        assertThat(service.applyPromotion(222, order.id(), future.code()).note()).contains("ещё не действует");
    }

    @Test
    void reservationsProtectTheUserQuotaAndCancellationOrExpiryFreesIt() throws Exception {
        PromoCode code = create("RACE", PromoType.COMBINED, 1, 1, "Мест больше нет");
        PrintOrder left = draft(222);
        PrintOrder right = draft(333);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.applyPromotion(222, left.id(), code.code()));
            var b = pool.submit(() -> service.applyPromotion(333, right.id(), code.code()));
            assertThat(List.of(a.get(), b.get())).filteredOn(r -> r.applied()).hasSize(1);
        }
        assertThat(count("promotion_reservations")).isEqualTo(1);
        PrintOrder reserved = left;
        long actor = 222;
        PrintOrder waiting = right;
        long waitingActor = 333;
        if (orders.get(right.id()).promoCode().equals(code.code())) {
            reserved = right; actor = 333; waiting = left; waitingActor = 222;
        }
        assertThat(service.applyPromotion(waitingActor, waiting.id(), code.code()).note()).isEqualTo("Мест больше нет");
        service.cancel(actor, reserved.id());
        assertThat(service.applyPromotion(waitingActor, waiting.id(), code.code()).applied()).isTrue();
        clock.set(clock.instant().plus(25, ChronoUnit.HOURS));
        PrintOrder replacement = draft(444);
        assertThat(service.applyPromotion(444, replacement.id(), code.code()).applied()).isTrue();
        assertThat(codes.get(code.id()).usedUsers()).isZero();
    }

    @Test
    void welcomeCampaignOpensOnlyAfterTwoPaymentsAndCountsEightOtherUsers() {
        PrintOrder waiting = draft(999);
        assertThat(service.applyPromotion(999, waiting.id(), "FIRST30").note()).contains("после двух оплат");
        for (long user : List.of(222L, 333L)) {
            PrintOrder order = draft(user);
            assertThat(service.applyPromotion(user, order.id(), "FIRST50").applied()).isTrue();
            pay(user, order);
        }
        PromoCode first50 = codes.byCode("FIRST50");
        assertThat(first50.usedUsers()).isEqualTo(2);
        assertThat(first50.usedOrders()).isEqualTo(2);
        PrintOrder previous = draft(222);
        assertThat(service.applyPromotion(222, previous.id(), "FIRST30").note()).contains("один промокод");
        assertThat(service.applyPromotion(999, waiting.id(), "FIRST50").applied()).isFalse();
        assertThat(service.applyPromotion(999, waiting.id(), "FIRST30").applied()).isTrue();
        for (long user = 400; user < 407; user++) {
            PrintOrder order = draft(user);
            assertThat(service.applyPromotion(user, order.id(), "FIRST30").applied()).isTrue();
        }
        PrintOrder ninth = draft(888);
        assertThat(service.applyPromotion(888, ninth.id(), "FIRST30").note()).contains("места");
        assertThat(codes.byCode("FIRST30").usedUsers()).isZero();
    }

    @Test
    void firstOrderAndSubsequentOrdersUsePaidHistoryAndPerUserOrderLimits() {
        PrintOrder first = draft(222);
        assertThat(service.applyPromotion(222, first.id(), "TESTSEVERALORDERS").note()).contains("после первого оплаченного");
        assertThat(service.applyPromotion(222, first.id(), "TESTFIRSTORDER").applied()).isTrue();
        pay(222, first);
        PrintOrder next = draft(222);
        assertThat(service.applyPromotion(222, next.id(), "TESTFIRSTORDER").note()).contains("только для первого");
        for (int index = 0; index < 3; index++) {
            if (index > 0) next = draft(222);
            assertThat(service.applyPromotion(222, next.id(), "TESTSEVERALORDERS").applied()).isTrue();
            pay(222, next);
        }
        PromoCode code = codes.byCode("TESTSEVERALORDERS");
        assertThat(code.usedUsers()).isEqualTo(1);
        assertThat(code.usedOrders()).isEqualTo(3);
        PrintOrder fourth = draft(222);
        assertThat(service.applyPromotion(222, fourth.id(), code.code()).note()).contains("максимально");
        assertThat(count("payments")).isEqualTo(4);
    }

    @Test
    void adminWizardValidatesAndSupportsCreateEditArchiveRestoreAndAuthorization() {
        click(222, "pc:new");
        assertThat(dialogs.get(222, 222)).isNull();
        click(111, -12345, "pc:new");
        group(111, java.util.Map.of("text", "QA_CODE"));
        click(111, -12345, "pc:type:COMBINED");
        group(111, java.util.Map.of("text", "101"));
        assertThat(dialogs.get(111, -12345).step()).isEqualTo("percent");
        group(111, java.util.Map.of("text", "12,5"));
        click(111, -12345, "pc:today");
        group(111, java.util.Map.of("text", "31.12.2026"));
        group(111, java.util.Map.of("text", "2"));
        group(111, java.util.Map.of("text", "3"));
        click(111, -12345, "pc:skip");
        assertThat(codes.byCode("QA_CODE")).isNull();
        click(111, -12345, "pc:save");
        PromoCode saved = codes.byCode("QA_CODE");
        assertThat(saved.percent()).isEqualByComparingTo("12.5");
        assertThat(saved.maxUsers()).isEqualTo(2);
        assertThat(saved.maxOrdersPerUser()).isEqualTo(3);
        assertThat(saved.expiresAt()).isEqualTo(Instant.parse("2026-12-31T21:00:00Z"));
        click(111, -12345, "pc:toggle:" + saved.id());
        assertThat(codes.get(saved.id()).enabled()).isFalse();
        click(111, -12345, "pc:delete:" + saved.id());
        assertThat(codes.get(saved.id()).deleted()).isFalse();
        click(111, -12345, "pc:deleteYes:" + saved.id());
        assertThat(codes.get(saved.id()).deleted()).isTrue();
        click(111, -12345, "pc:restore:" + saved.id());
        assertThat(codes.get(saved.id()).deleted()).isFalse();
        click(111, -12345, "pc:edit:" + saved.id());
        group(111, java.util.Map.of("text", "QA_CODE"));
        click(111, -12345, "pc:type:DATE");
        group(111, java.util.Map.of("text", "15"));
        click(111, -12345, "pc:today");
        group(111, java.util.Map.of("text", "30.11.2026"));
        group(111, java.util.Map.of("text", "1"));
        click(111, -12345, "pc:skip");
        click(111, -12345, "pc:save");
        assertThat(codes.get(saved.id()).type()).isEqualTo(PromoType.DATE);
        assertThat(codes.get(saved.id()).percent()).isEqualByComparingTo("15");
        assertThat(codes.get(saved.id()).enabled()).isFalse();
        assertThatThrownBy(() -> catalog.toggle(222, saved.id())).isInstanceOf(BusinessException.class);
        assertThat(codes.get(saved.id()).createdAt()).isEqualTo(saved.createdAt());
    }

    private PromoCode create(String name, PromoType type, Integer users, int orders, String reason) {
        return catalog.save(111, new PromoCode(null, name, type, new BigDecimal("10"), clock.instant(),
                clock.instant(), clock.instant().plus(30, ChronoUnit.DAYS), true, false, reason, users, orders,
                0, 0, null, null, 0));
    }
}
