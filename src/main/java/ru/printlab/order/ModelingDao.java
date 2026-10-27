package ru.printlab.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ModelingDao {
    private final JdbcClient jdbc;

    public ModelingDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID pending(UUID customer) {
        return jdbc.sql("SELECT id FROM orders WHERE customer_id = ? AND status = 'MODELING' ORDER BY number DESC LIMIT 1")
                .param(customer)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    public void add(UUID order, ModelingPhoto photo, Instant now) {
        jdbc.sql("INSERT INTO modeling_photos(id, order_id, telegram_file_id, document, caption, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(UUID.randomUUID())
                .param(order)
                .param(photo.fileId())
                .param(photo.document())
                .param(photo.caption())
                .param(Timestamp.from(now))
                .update();
    }

    public List<ModelingPhoto> photos(UUID order) {
        return jdbc.sql("SELECT telegram_file_id, document, caption FROM modeling_photos WHERE order_id = ? ORDER BY created_at, id")
                .param(order)
                .query((rs, row) -> new ModelingPhoto(rs.getString("telegram_file_id"), rs.getBoolean("document"), rs.getString("caption")))
                .list();
    }

    public void postponeReceipt(UUID order, Instant dueAt) {
        removeReceipt(order);
        jdbc.sql("INSERT INTO modeling_receipts(order_id, due_at) VALUES (?, ?)")
                .param(order)
                .param(Timestamp.from(dueAt))
                .update();
    }

    public List<UUID> dueReceipts(Instant now) {
        return jdbc.sql("SELECT order_id FROM modeling_receipts WHERE due_at <= ? ORDER BY due_at")
                .param(Timestamp.from(now))
                .query(UUID.class)
                .list();
    }

    public void removeReceipt(UUID order) {
        jdbc.sql("DELETE FROM modeling_receipts WHERE order_id = ?")
                .param(order)
                .update();
    }
}
