package ru.printlab.slicing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.printlab.common.BusinessException;
import ru.printlab.order.OrderDao;
import ru.printlab.order.OrderStatus;
import ru.printlab.order.PrintOrder;

@Component
@ConditionalOnProperty(name = "printlab.slicer.enabled", havingValue = "true")
public class SlicingWorker {
    private final SlicingTransactions transactions;
    private final SlicingEngine engine;
    private final OrderDao orders;

    public SlicingWorker(SlicingTransactions transactions, SlicingEngine engine, OrderDao orders) {
        this.transactions = transactions;
        this.engine = engine;
        this.orders = orders;
    }

    @Scheduled(fixedDelay = 2000)
    public void work() {
        SliceJob job = transactions.claim();
        if (job == null) {
            return;
        }
        try {
            PrintOrder order = orders.get(job.orderId());
            if (order.status() != OrderStatus.ESTIMATING || job.attempts() >= 3) {
                transactions.fail(job, "Заявка изменена или превышен лимит повторов после перезапуска.");
                return;
            }
            SliceResult result = engine.slice(job, order, orders.model(order.modelId()));
            transactions.complete(job, result);
        } catch (BusinessException exception) {
            transactions.fail(job, exception.getMessage());
        } catch (RuntimeException exception) {
            transactions.fail(job, "Внутренняя ошибка слайсинга: " + exception.getClass().getSimpleName());
        }
    }
}
