package ru.printlab.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Repository
public class DialogDao {
    private final JdbcClient jdbc;
    private final Clock clock;

    public DialogDao(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public BotDialog get(long user, long chat) {
        return jdbc.sql("SELECT * FROM bot_dialogs WHERE user_id = ? AND chat_id = ? AND updated_at > ?")
                .param(user)
                .param(chat)
                .param(Timestamp.from(clock.instant().minusSeconds(86400)))
                .query((rs, row) -> new BotDialog(rs.getString("kind"), rs.getString("step"),
                        rs.getObject("order_id", UUID.class), rs.getString("payload")))
                .optional()
                .orElse(null);
    }

    public void save(long user, long chat, String kind, String step, UUID orderId, String payload) {
        clear(user, chat);
        jdbc.sql("INSERT INTO bot_dialogs(user_id, chat_id, kind, step, order_id, payload, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(user)
                .param(chat)
                .param(kind)
                .param(step)
                .param(orderId)
                .param(payload)
                .param(Timestamp.from(clock.instant()))
                .update();
    }

    public void clear(long user, long chat) {
        jdbc.sql("DELETE FROM bot_dialogs WHERE user_id = ? AND chat_id = ?")
                .param(user)
                .param(chat)
                .update();
    }

    public UUID replyOrder(long chat, long message) {
        return jdbc.sql("SELECT order_id FROM order_messages WHERE chat_id = ? AND message_id = ?")
                .param(chat)
                .param(message)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }
}
