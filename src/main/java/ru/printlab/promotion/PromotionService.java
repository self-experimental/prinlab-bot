package ru.printlab.promotion;

import org.springframework.stereotype.Service;
import ru.printlab.config.AppProperties;
import ru.printlab.config.PromotionPolicy;
import ru.printlab.order.PrintOrder;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class PromotionService {
    private final AppProperties properties;
    private final PromotionDao dao;

    public PromotionService(AppProperties properties, PromotionDao dao) {
        this.properties = properties;
        this.dao = dao;
    }

    /** Caller holds business_lock for the entire quote or payment transaction. */
    public PromotionResult check(PrintOrder order, boolean newCustomer, BigDecimal discount, Instant now) {
        PromotionPolicy policy = properties.promotion();
        dao.releaseExpired(now);
        if (order.promoCode().isBlank()) {
            return new PromotionResult(false, "Без промокода");
        }
        if (!order.promoCode().equals(policy.code())) {
            return new PromotionResult(false, "Промокод не найден; рассчитана обычная цена");
        }
        if (!policy.enabled() || !now.isBefore(policy.endsAt())) {
            return new PromotionResult(false, "Акция не действует; рассчитана обычная цена");
        }
        if (discount.signum() == 0) {
            return new PromotionResult(false, "В этом расчёте нет суммы печати для скидки; лимит акции не используется");
        }
        if (!newCustomer || dao.customerReserved(order.customerId(), policy.code(), order.id())) {
            return new PromotionResult(false, "Акция доступна на первый оплаченный заказ; возможен резерв в другой заявке");
        }
        if (dao.usedSlots(policy.code(), order.id()) >= policy.maxPaidOrders()
                || dao.usedBudget(policy.code(), order.id()).add(discount).compareTo(policy.budget()) > 0) {
            return new PromotionResult(false, "Лимит акции занят; рассчитана обычная цена");
        }
        String note = policy.code() + ": скидка на печать, доставка без скидки";
        if (!properties.rates().discountLaunchFee()) {
            note += ", запуск без скидки";
        }
        return new PromotionResult(true, note);
    }
}
