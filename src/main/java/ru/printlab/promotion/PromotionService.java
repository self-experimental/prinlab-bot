package ru.printlab.promotion;

import org.springframework.stereotype.Service;
import ru.printlab.config.PromotionPolicy;
import ru.printlab.order.PrintOrder;
import ru.printlab.order.OrderDao;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

@Service
public class PromotionService {
    private final PromoCodeDao codes;
    private final PromotionDao usage;
    private final OrderDao orders;

    public PromotionService(PromoCodeDao codes, PromotionDao usage, OrderDao orders) {
        this.codes = codes;
        this.usage = usage;
        this.orders = orders;
    }

    public PromoCode definition(String value) {
        return codes.byCode(value.trim().toUpperCase(Locale.ROOT));
    }

    public PromotionPolicy policy(String value) {
        PromoCode p = definition(value);
        if (p == null) return new PromotionPolicy(false, "NONE", BigDecimal.ZERO, null, Integer.MAX_VALUE, null, Instant.parse("2100-01-01T00:00:00Z"));
        int max = Integer.MAX_VALUE;
        if (p.maxUsers() != null) max = p.maxUsers();
        return new PromotionPolicy(p.enabled() && !p.deleted(), p.code(), p.percent().movePointLeft(2), null, max, null, p.expiresAt());
    }

    // Caller holds business_lock; only confirmed paid redemptions consume limits.
    public PromotionResult eligibility(PrintOrder order, String value, Instant now) {
        usage.releaseExpired(now);
        if (value.isBlank() || value.equals("-")) return new PromotionResult(false, "Без промокода");
        PromoCode p = definition(value);
        if (p == null) return new PromotionResult(false, "Промокод не найден.");
        if (now.isBefore(p.createdAt()) || now.isBefore(p.startsAt())) return new PromotionResult(false, "Промокод ещё не действует.");
        if (!now.isBefore(p.expiresAt())) return new PromotionResult(false, "Промокод просрочен и больше не действует.");
        if (!p.enabled() || p.deleted()) return denied(p, "Промокод больше не действует.");
        if (p.prerequisiteCode() != null && usage.paidSlots(p.prerequisiteCode()) < p.prerequisitePaidUsers())
            return denied(p, p.code().toLowerCase(Locale.ROOT) + " станет доступен после двух оплат с first50.");
        long paid = orders.paidCount(order.customerId());
        if (p.type() == PromoType.FIRST_ORDER && paid > 0) return denied(p, "Этот промокод доступен только для первого заказа.");
        if (p.type() == PromoType.SUBSEQUENT_ORDERS && paid == 0) return denied(p, "Этот промокод доступен после первого оплаченного заказа.");
        long own = usage.customerUses(p.id(), order.customerId(), order.id());
        if (own >= p.maxOrdersPerUser()) return denied(p, "Ты уже использовал этот промокод максимально допустимое число раз.");
        if (p.campaignGroup() != null && !p.campaignGroup().isBlank() && usage.campaignUsed(p.campaignGroup(), p.id(), order.customerId(), order.id()))
            return denied(p, "Можно использовать только один промокод этой акции на человека.");
        if (p.maxUsers() != null && own == 0 && usage.participants(p.id(), order.id()) >= p.maxUsers())
            return denied(p, "Лимит оплаченных применений этого промокода исчерпан.");
        return new PromotionResult(true, p.code() + ": скидка " + p.percent().stripTrailingZeros().toPlainString().replace('.', ',') + "% на печать. Доставка оплачивается отдельно.");
    }

    public PromotionResult check(PrintOrder order, BigDecimal discount, Instant now) {
        PromotionResult eligible = eligibility(order, order.promoCode(), now);
        if (eligible.applied() && discount.signum() <= 0) return new PromotionResult(false, "В расчёте нет стоимости печати для скидки.");
        return eligible;
    }

    private PromotionResult denied(PromoCode p, String reason) {
        if (p.failureReason() != null && !p.failureReason().isBlank()) reason = p.failureReason();
        return new PromotionResult(false, reason);
    }
}
