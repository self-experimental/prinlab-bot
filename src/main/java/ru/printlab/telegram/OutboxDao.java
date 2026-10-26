package ru.printlab.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.printlab.common.Json;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Repository
public class OutboxDao {
    private final JdbcClient jdbc;
    private final Json json;

    public OutboxDao(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void add(String method, Map<String, Object> payload, Instant now) {
        jdbc.sql("INSERT INTO outbox(method, payload, next_attempt) VALUES (?, ?, ?)")
                .param(method)
                .param(json.write(payload))
                .param(Timestamp.from(now))
                .update();
    }

    public OutboxEntry next(Instant now) {
        return jdbc.sql("SELECT * FROM outbox WHERE delivered = FALSE AND failed = FALSE AND next_attempt <= ? ORDER BY id LIMIT 1 FOR UPDATE")
                .param(Timestamp.from(now))
                .query((rs, row) -> new OutboxEntry(rs.getLong("id"), rs.getString("method"),
                        rs.getString("payload"), rs.getInt("attempts")))
                .optional()
                .orElse(null);
    }

    public void delivered(long id) {
        jdbc.sql("UPDATE outbox SET delivered = TRUE WHERE id = ?")
                .param(id)
                .update();
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
