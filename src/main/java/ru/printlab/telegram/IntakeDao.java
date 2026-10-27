package ru.printlab.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class IntakeDao {
    public record Intake(UUID orderId, String mode) {}
    private final JdbcClient jdbc;

    public IntakeDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Intake get(long user) {
        return jdbc.sql("SELECT i.order_id, i.mode FROM customer_intake i JOIN orders o ON o.id = i.order_id WHERE i.user_id = ? AND o.deleted = FALSE")
                .param(user)
                .query((rs, row) -> new Intake(rs.getObject("order_id", UUID.class), rs.getString("mode")))
                .optional()
                .orElse(null);
    }

    public void select(long user, UUID order, String mode) {
        clear(user);
        jdbc.sql("INSERT INTO customer_intake(user_id, order_id, mode) VALUES (?, ?, ?)")
                .param(user)
                .param(order)
                .param(mode)
                .update();
    }

    public void clear(long user) {
        jdbc.sql("DELETE FROM customer_intake WHERE user_id = ?")
                .param(user)
                .update();
    }
}
