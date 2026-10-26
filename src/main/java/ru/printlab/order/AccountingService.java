package ru.printlab.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.common.Money;
import ru.printlab.pricing.CostBreakdown;
import ru.printlab.pricing.Estimate;
import ru.printlab.pricing.PricingService;
import ru.printlab.pricing.PricingSnapshot;

import java.math.BigDecimal;
import java.time.Clock;

@Service
public class AccountingService {
    private final OrderService service;
    private final OrderDao orders;
    private final AccountingDao accounting;
    private final PricingService pricing;
    private final Json json;
    private final Clock clock;

    public AccountingService(OrderService service, OrderDao orders, AccountingDao accounting,
                             PricingService pricing, Json json, Clock clock) {
        this.service = service;
        this.orders = orders;
        this.accounting = accounting;
        this.pricing = pricing;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public ActualCosts actual(long admin, long number, Estimate consumed, BigDecimal extra) {
        service.requireAdmin(admin);
        Money.nonNegative(extra, "Дополнительные расходы");
        orders.lock();
        PrintOrder order = orders.byNumber(number);
        if (order.paidAt() == null) {
            throw new BusinessException("Факт можно записать после подтверждения оплаты.");
        }
        PricingSnapshot snapshot = orders.quote(order.quoteId()).snapshot();
        CostBreakdown costs = pricing.costs(snapshot.material(), snapshot.rates(), consumed, snapshot.newCustomer(), false);
        BigDecimal total = Money.rub(costs.total().add(extra));
        BigDecimal contribution = Money.rub(snapshot.publicPrice().total().subtract(total)
                .subtract(snapshot.economics().paymentFee()).subtract(snapshot.economics().estimatedTax()));
        ActualCosts actual = new ActualCosts(consumed, costs, extra, total, contribution);
        accounting.actual(order.id(), actual, clock.instant());
        orders.event(order.id(), admin, "ACTUAL_COSTS", json.write(actual), clock.instant());
        return actual;
    }

    public String report(long admin) {
        service.requireAdmin(admin);
        return "Отчёт за всё время\nКлиентов: " + accounting.customers() + "\nОтправленных заявок: " + accounting.orders()
                + "\nОплаченных заказов: " + accounting.payments()
                + "\nПоступило: " + Money.format(accounting.revenue())
                + "\nПолные возвраты: " + Money.format(accounting.refunds())
                + "\nПлановый маржинальный доход без возвращённых заказов: " + Money.format(accounting.plannedContribution())
                + "\nПо фактическому расходу (" + accounting.actualCount() + " заказов): " + Money.format(accounting.actualContribution())
                + "\nОценка по сохранённым ставкам, до постоянных расходов. Стоимость труда берётся из снимка заказа."
                + "\nРасходы возвращённых заказов требуют отдельного учёта. Это не чистая прибыль."
                + "\nИсточники первого запуска (это не сканы QR):\n" + accounting.campaigns();
    }
}
