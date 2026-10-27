package ru.printlab.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.printlab.common.Json;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxDao {
    private final JdbcClient jdbc;
    private final Json json;

    public OutboxDao(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void add(String method, Map<String, Object> payload, Instant now) {
        add(method, payload, now, null);
    }

    public void add(String method, Map<String, Object> payload, Instant now, UUID orderId) {
        jdbc.sql("INSERT INTO outbox(method, payload, next_attempt, order_id) VALUES (?, ?, ?, ?)")
                .param(method)
                .param(json.write(payload))
                .param(Timestamp.from(now))
                .param(orderId)
                .update();
    }

    public OutboxEntry next(Instant now) {
        return jdbc.sql("SELECT * FROM outbox WHERE delivered = FALSE AND failed = FALSE AND next_attempt <= ? ORDER BY id LIMIT 1 FOR UPDATE")
                .param(Timestamp.from(now))
                .query((rs, row) -> new OutboxEntry(rs.getLong("id"), rs.getString("method"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getObject("order_id", UUID.class)))
                .optional()
                .orElse(null);
    }

    public void delivered(long id) {
        jdbc.sql("UPDATE outbox SET delivered = TRUE WHERE id = ?")
                .param(id)
                .update();
    }

    public void remember(long chat, long message, UUID orderId) {
        long count = jdbc.sql("SELECT COUNT(*) FROM order_messages WHERE chat_id = ? AND message_id = ?")
                .param(chat)
                .param(message)
                .query(Long.class)
                .single();
        if (count == 0) {
            jdbc.sql("INSERT INTO order_messages(chat_id, message_id, order_id) VALUES (?, ?, ?)")
                    .param(chat)
                    .param(message)
                    .param(orderId)
                    .update();
        }
    }

    public void retry(long id, Instant retryAt, boolean permanent) {
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, next_attempt = ?, failed = ? WHERE id = ?")
                .param(Timestamp.from(retryAt))
                .param(permanent)
                .param(id)
                .update();
    }

    public long failedCount() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE failed = TRUE")
                .query(Long.class)
                .single();
    }

    public void retryFailed() {
        jdbc.sql("UPDATE outbox SET failed = FALSE, attempts = 0 WHERE failed = TRUE")
                .update();
    }
}
