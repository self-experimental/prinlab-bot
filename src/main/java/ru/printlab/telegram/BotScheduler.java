package ru.printlab.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.printlab.order.OrderService;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "printlab.bot-enabled", havingValue = "true")
public class BotScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(BotScheduler.class);
    private final TelegramGateway gateway;
    private final UpdateDao updates;
    private final UpdateProcessor processor;
    private final OutboxWorker outbox;
    private final OrderService orders;

    public BotScheduler(TelegramGateway gateway, UpdateDao updates, UpdateProcessor processor,
                        OutboxWorker outbox, OrderService orders) {
        this.gateway = gateway;
        this.updates = updates;
        this.processor = processor;
        this.outbox = outbox;
        this.orders = orders;
    }

    @Scheduled(fixedDelay = 1500)
    public void poll() {
        try {
            for (JsonNode update : gateway.updates(updates.offset())) {
                processor.process(update);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Telegram polling paused at offset={}; errorType={}", updates.offset(), exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 500)
    public void deliver() {
        for (int i = 0; i < 10; i++) {
            if (!outbox.deliverOne()) {
                break;
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void expire() {
        orders.expire();
    }
}
