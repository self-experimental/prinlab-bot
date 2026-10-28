package ru.printlab.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.printlab.common.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class ColorRequestDao {
    private final JdbcClient jdbc;
    public ColorRequestDao(JdbcClient jdbc) { this.jdbc = jdbc; }

    public ColorRequest get(UUID id) {
        return jdbc.sql("SELECT * FROM color_requests WHERE id = ?").param(id)
                .query((rs, row) -> new ColorRequest(id, rs.getObject("order_id", UUID.class), rs.getString("description"),
                        rs.getString("hex_code"), rs.getString("status"), rs.getObject("approved_color_id", UUID.class)))
                .optional().orElseThrow(() -> new BusinessException("Предложение цвета не найдено."));
    }

    public ColorRequest create(UUID order, String description, String hex, Instant now) {
        supersede(order);
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO color_requests(id, order_id, description, hex_code, status, created_at) VALUES (?, ?, ?, ?, 'PENDING', ?)")
                .param(id).param(order).param(description).param(hex).param(Timestamp.from(now)).update();
        return get(id);
    }

    public void supersede(UUID order) {
        jdbc.sql("UPDATE color_requests SET status = 'SUPERSEDED' WHERE order_id = ? AND status = 'PENDING'")
                .param(order).update();
    }

    public boolean approved(UUID order, UUID color) {
        return jdbc.sql("SELECT COUNT(*) FROM color_requests WHERE order_id = ? AND approved_color_id = ? AND status = 'APPROVED'")
                .param(order).param(color).query(Long.class).single() > 0;
    }

    public void resolve(UUID id, String status, UUID color, Instant now) {
        jdbc.sql("UPDATE color_requests SET status = ?, approved_color_id = ?, resolved_at = ? WHERE id = ? AND status = 'PENDING'")
                .param(status).param(color).param(Timestamp.from(now)).param(id).update();
    }
}
