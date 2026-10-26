package ru.printlab.slicing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.Json;
import ru.printlab.order.OrderDao;
import ru.printlab.order.OrderService;
import ru.printlab.order.OrderStatus;
import ru.printlab.order.PrintOrder;
import ru.printlab.telegram.Notifications;

import java.time.Clock;

@Service
public class SlicingTransactions {
    private final SlicingDao jobs;
    private final OrderDao orders;
    private final OrderService service;
    private final Notifications messages;
    private final SlicerProperties properties;
    private final Json json;
    private final Clock clock;

    public SlicingTransactions(SlicingDao jobs, OrderDao orders, OrderService service, Notifications messages,
                               SlicerProperties properties, Json json, Clock clock) {
        this.jobs = jobs;
        this.orders = orders;
        this.service = service;
        this.messages = messages;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public SliceJob claim() {
        orders.lock();
        return jobs.claim(clock.instant(), properties.timeoutSeconds());
    }

    @Transactional
    public void complete(SliceJob job, SliceResult result) {
        orders.lock();
        if (!jobs.running(job.id())) {
            return;
        }
        service.automaticEstimate(job.orderId(), result.estimate(), result.provenance());
        jobs.finish(job.id(), "DONE", json.write(result));
    }

    @Transactional
    public void fail(SliceJob job, String reason) {
        orders.lock();
        if (!jobs.running(job.id())) {
            return;
        }
        jobs.finish(job.id(), "FAILED", reason);
        PrintOrder order = orders.get(job.orderId());
        if (order.status() == OrderStatus.ESTIMATING) {
            messages.text(order.customerId(), "Заказ №" + order.number() + ": автоматический расчёт не завершён. Оператор проверит модель.");
            messages.admins("Не удалось рассчитать №" + order.number() + ": " + reason + "\nПовтор: /reslice "
                    + order.number() + "; ручной резерв: /slice. Файл отправлен ниже.");
            messages.fileToAdmins(orders.model(order.modelId()).telegramFileId(), "Модель заказа №" + order.number());
        }
    }
}
