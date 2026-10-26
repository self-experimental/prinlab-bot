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
}
