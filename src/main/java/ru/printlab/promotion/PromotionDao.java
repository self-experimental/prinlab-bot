package ru.printlab.promotion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class PromotionDao {
    private final JdbcClient jdbc;

    public PromotionDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void releaseExpired(Instant now) {
        jdbc.sql("DELETE FROM promotion_reservations WHERE redeemed = FALSE AND expires_at <= ?")
                .param(Timestamp.from(now))
                .update();
    }

    public void release(UUID orderId) {
        jdbc.sql("DELETE FROM promotion_reservations WHERE order_id = ? AND redeemed = FALSE")
                .param(orderId)
                .update();
    }

    public boolean customerReserved(UUID customer, UUID exceptOrder) {
        return jdbc.sql("SELECT COUNT(*) FROM promotion_reservations WHERE customer_id = ? AND order_id <> ?")
                .param(customer)
                .param(exceptOrder)
                .query(Long.class)
                .single() > 0;
    }

    public long paidSlots(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM promotion_reservations WHERE code = ? AND redeemed = TRUE")
                .param(code)
                .query(Long.class)
                .single();
    }

    public long usedSlots(String code, UUID exceptOrder) {
        return jdbc.sql("SELECT COUNT(*) FROM promotion_reservations WHERE code = ? AND order_id <> ?")
                .param(code)
                .param(exceptOrder)
                .query(Long.class)
                .single();
    }

    public BigDecimal usedBudget(String code, UUID exceptOrder) {
        return jdbc.sql("SELECT COALESCE(SUM(discount), 0) FROM promotion_reservations WHERE code = ? AND order_id <> ?")
                .param(code)
                .param(exceptOrder)
                .query(BigDecimal.class)
                .single();
    }

    public void reserve(UUID orderId, UUID customer, String code, BigDecimal discount, Instant expiry) {
        release(orderId);
        jdbc.sql("INSERT INTO promotion_reservations(order_id, customer_id, code, discount, expires_at) VALUES (?, ?, ?, ?, ?)")
                .param(orderId)
                .param(customer)
                .param(code)
                .param(discount)
                .param(Timestamp.from(expiry))
                .update();
    }

    public boolean hasReservation(UUID orderId, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM promotion_reservations WHERE order_id = ? AND redeemed = FALSE AND expires_at > ?")
                .param(orderId)
                .param(Timestamp.from(now))
                .query(Long.class)
                .single() > 0;
    }

    public void redeem(UUID orderId) {
        jdbc.sql("UPDATE promotion_reservations SET redeemed = TRUE WHERE order_id = ?")
                .param(orderId)
                .update();
    }
}
