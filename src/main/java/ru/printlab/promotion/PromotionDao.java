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
        return jdbc.sql("SELECT COUNT(DISTINCT customer_id) FROM promotion_reservations WHERE code = ? AND redeemed = TRUE")
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
        jdbc.sql("INSERT INTO promotion_reservations(order_id, customer_id, code, discount, expires_at, promotion_id) VALUES (?, ?, ?, ?, ?, (SELECT id FROM promo_codes WHERE code = ?))")
                .param(orderId)
                .param(customer)
                .param(code)
                .param(discount)
                .param(Timestamp.from(expiry))
                .param(code)
                .update();
    }

    public boolean hasReservation(UUID orderId, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM promotion_reservations WHERE order_id = ? AND redeemed = FALSE AND expires_at > ?")
                .param(orderId)
                .param(Timestamp.from(now))
                .query(Long.class)
                .single() > 0;
    }

    public long customerUses(UUID promotion, UUID customer, UUID exceptOrder) {
        return jdbc.sql("SELECT COUNT(*) FROM promotion_reservations WHERE promotion_id = ? AND customer_id = ? AND order_id <> ?")
                .param(promotion).param(customer).param(exceptOrder).query(Long.class).single();
    }

    public long participants(UUID promotion, UUID exceptOrder) {
        return jdbc.sql("SELECT COUNT(DISTINCT customer_id) FROM promotion_reservations WHERE promotion_id = ? AND order_id <> ?")
                .param(promotion).param(exceptOrder).query(Long.class).single();
    }

    public boolean campaignUsed(String group, UUID promotion, UUID customer, UUID exceptOrder) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM promotion_reservations r JOIN promo_codes p ON p.id = r.promotion_id
                WHERE p.campaign_group = ? AND r.customer_id = ? AND r.order_id <> ? AND p.id <> ?
                """)
                .param(group).param(customer).param(exceptOrder).param(promotion).query(Long.class).single() > 0;
    }

    public long reservations(UUID promotion) {
        return jdbc.sql("SELECT COUNT(*) FROM promotion_reservations WHERE promotion_id = ?")
                .param(promotion).query(Long.class).single();
    }

    public void redeem(UUID orderId) {
        int updated = jdbc.sql("UPDATE promotion_reservations SET redeemed = TRUE WHERE order_id = ? AND redeemed = FALSE")
                .param(orderId)
                .update();
        if (updated > 0) {
            jdbc.sql("""
                    UPDATE promo_codes SET used_users = (SELECT COUNT(DISTINCT customer_id) FROM promotion_reservations WHERE promotion_id = promo_codes.id AND redeemed = TRUE),
                    used_orders = (SELECT COUNT(*) FROM promotion_reservations WHERE promotion_id = promo_codes.id AND redeemed = TRUE)
                    WHERE id = (SELECT promotion_id FROM promotion_reservations WHERE order_id = ?)
                    """)
                    .param(orderId)
                    .update();
        }
    }
}
