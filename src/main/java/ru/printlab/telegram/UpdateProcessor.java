package ru.printlab.telegram;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.order.OrderDao;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.Map;

@Service
public class UpdateProcessor {
    private final UpdateDao updates;
    private final OrderDao orders;
    private final BotCommands commands;
    private final Notifications messages;
    private final OutboxDao outbox;
    private final Clock clock;

    public UpdateProcessor(UpdateDao updates, OrderDao orders, BotCommands commands, Notifications messages,
                           OutboxDao outbox, Clock clock) {
        this.updates = updates;
        this.orders = orders;
        this.commands = commands;
        this.messages = messages;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void process(JsonNode update) {
        long id = update.path("update_id").asLong(-1);
        if (id < updates.lockOffset()) {
            return;
        }
        JsonNode message = update.path("message");
        JsonNode from = message.path("from");
        String callback = null;
        if (update.has("callback_query")) {
            JsonNode query = update.path("callback_query");
            from = query.path("from");
            message = query.path("message");
            callback = query.path("data").asText("");
            outbox.add("answerCallbackQuery", Map.of("callback_query_id", query.path("id").asText()), clock.instant());
        }
        long user = from.path("id").asLong();
        if (user > 0 && message.path("chat").path("id").asLong() == user
                && message.path("chat").path("type").asText().equals("private") && !from.path("is_bot").asBoolean()) {
            orders.lock();
            String campaign = null;
            String text = message.path("text").asText("");
            if (text.matches("/start [A-Za-z0-9_-]{1,64}")) {
                campaign = text.substring(7);
            }
            String name = from.path("first_name").asText("Клиент");
            if (name.length() > 256) {
                name = name.substring(0, 256);
            }
            orders.register(user, name, campaign, clock.instant());
            try {
                commands.handle(user, message, callback);
            } catch (BusinessException exception) {
                messages.text(user, exception.getMessage());
            }
        }
        updates.advance(id + 1);
    }
}
