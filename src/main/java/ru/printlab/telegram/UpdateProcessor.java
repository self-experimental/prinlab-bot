package ru.printlab.telegram;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.printlab.common.BusinessException;
import ru.printlab.order.OrderDao;
import ru.printlab.operator.OperatorAccess;
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
    private final OperatorAccess operators;

    public UpdateProcessor(UpdateDao updates, OrderDao orders, BotCommands commands, Notifications messages,
                           OutboxDao outbox, Clock clock, OperatorAccess operators) {
        this.updates = updates;
        this.orders = orders;
        this.commands = commands;
        this.messages = messages;
        this.outbox = outbox;
        this.clock = clock;
        this.operators = operators;
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
        long chat = message.path("chat").path("id").asLong();
        if (message.has("migrate_to_chat_id")) {
            orders.lock();
            operators.migrate(chat, message.path("migrate_to_chat_id").asLong());
        }
        String chatType = message.path("chat").path("type").asText();
        if (user > 0 && !from.path("is_bot").asBoolean() && chat < 0
                && (chatType.equals("group") || chatType.equals("supergroup"))
                && message.path("text").asText("").trim().matches("/operator_chat(?:@[A-Za-z0-9_]+)?")) {
            orders.lock();
            operators.enroll(user, from.path("username").asText(""));
            if (operators.isAdmin(user)) {
                operators.group(chat);
                messages.text(chat, "Этот чат подключён для фото, заказов и уведомлений PrintLab. Откройте /admin для управления заказами и цветами.");
            } else {
                messages.text(chat, "Подключить чат может только настроенный оператор.");
            }
        }
        if (user > 0 && message.path("chat").path("id").asLong() == user
                && message.path("chat").path("type").asText().equals("private") && !from.path("is_bot").asBoolean()) {
            orders.lock();
            operators.enroll(user, from.path("username").asText(""));
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
        if (user > 0 && !from.path("is_bot").asBoolean()
                && chat < 0 && (chatType.equals("group") || chatType.equals("supergroup"))
                && operators.isGroup(chat)) {
            orders.lock();
            operators.enroll(user, from.path("username").asText(""));
            if (operators.isAdmin(user)) {
                String name = from.path("first_name").asText("Оператор");
                if (name.length() > 256) {
                    name = name.substring(0, 256);
                }
                orders.register(user, name, null, clock.instant());
                try {
                    commands.handleGroup(user, chat, message, callback);
                } catch (BusinessException exception) {
                    messages.text(chat, exception.getMessage());
                }
            }
        }
        updates.advance(id + 1);
    }
}
