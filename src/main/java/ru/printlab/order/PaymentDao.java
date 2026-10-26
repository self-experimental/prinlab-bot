package ru.printlab.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class PaymentDao {
    private final JdbcClient jdbc;

    public PaymentDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String reference(UUID orderId) {
        return jdbc.sql("SELECT reference FROM payments WHERE order_id = ?")
                .param(orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public boolean referenceUsed(String reference) {
        return jdbc.sql("SELECT COUNT(*) FROM payments WHERE reference = ? OR refund_reference = ?")
                .param(reference)
                .param(reference)
                .query(Long.class)
                .single() > 0;
    }

    public void paid(PrintOrder order, BigDecimal amount, String reference, UUID admin, Instant now) {
        jdbc.sql("INSERT INTO payments(order_id, reference, amount, confirmed_by, confirmed_at) VALUES (?, ?, ?, ?, ?)")
                .param(order.id())
                .param(reference)
                .param(amount)
                .param(admin)
                .param(Timestamp.from(now))
                .update();
        jdbc.sql("UPDATE orders SET status = 'PAID', paid_at = ? WHERE id = ?")
                .param(Timestamp.from(now))
                .param(order.id())
                .update();
    }

    public String refundReference(UUID orderId) {
        return jdbc.sql("SELECT refund_reference FROM payments WHERE order_id = ? AND refund_reference IS NOT NULL")
                .param(orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public void refund(UUID orderId, String reference, Instant now) {
        jdbc.sql("UPDATE payments SET refund_reference = ?, refunded_at = ? WHERE order_id = ?")
                .param(reference)
                .param(Timestamp.from(now))
                .param(orderId)
                .update();
    }
}
