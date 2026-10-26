package ru.printlab.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OutboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxDao dao;
    private final TelegramGateway gateway;
    private final Clock clock;

    public OutboxWorker(OutboxDao dao, TelegramGateway gateway, Clock clock) {
        this.dao = dao;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional
    public boolean deliverOne() {
        OutboxEntry entry = dao.next(clock.instant());
        if (entry == null) {
            return false;
        }
        try {
            gateway.deliver(entry.method(), entry.payload());
            dao.delivered(entry.id());
        } catch (TelegramFailure failure) {
            boolean permanent = failure.permanent() || entry.attempts() >= 9;
            int delay = Math.max(failure.retryAfter(), Math.min(3600, 10 * (entry.attempts() + 1) * (entry.attempts() + 1)));
            dao.retry(entry.id(), clock.instant().plusSeconds(delay), permanent);
            LOG.warn("Telegram delivery id={} failed; permanent={}", entry.id(), permanent);
        }
        return true;
    }
}
