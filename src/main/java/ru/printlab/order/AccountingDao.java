package ru.printlab.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.printlab.common.Json;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class AccountingDao {
    private final JdbcClient jdbc;
    private final Json json;

    public AccountingDao(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void actual(UUID orderId, ActualCosts actual, Instant now) {
        jdbc.sql("DELETE FROM order_actuals WHERE order_id = ?")
                .param(orderId)
                .update();
        jdbc.sql("INSERT INTO order_actuals(order_id, snapshot, total_cost, contribution, recorded_at) VALUES (?, ?, ?, ?, ?)")
                .param(orderId)
                .param(json.write(actual))
                .param(actual.totalCost())
                .param(actual.contribution())
                .param(Timestamp.from(now))
                .update();
    }

    public long customers() {
        return jdbc.sql("SELECT COUNT(*) FROM customers")
                .query(Long.class)
                .single();
    }

    public long orders() {
        return jdbc.sql("SELECT COUNT(*) FROM orders WHERE status <> 'DRAFT'")
                .query(Long.class)
                .single();
    }

    public long payments() {
        return jdbc.sql("SELECT COUNT(*) FROM payments")
                .query(Long.class)
                .single();
    }

    public BigDecimal revenue() {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM payments")
                .query(BigDecimal.class)
                .single();
    }

    public BigDecimal refunds() {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE refunded_at IS NOT NULL")
                .query(BigDecimal.class)
                .single();
    }

    public BigDecimal plannedContribution() {
        return jdbc.sql("""
                SELECT COALESCE(SUM(q.contribution), 0) FROM orders o
                JOIN quotes q ON q.id = o.quote_id JOIN payments p ON p.order_id = o.id
                WHERE p.refunded_at IS NULL
                """)
                .query(BigDecimal.class)
                .single();
    }

    public long actualCount() {
        return jdbc.sql("SELECT COUNT(*) FROM order_actuals a JOIN payments p ON p.order_id = a.order_id WHERE p.refunded_at IS NULL")
                .query(Long.class)
                .single();
    }

    public BigDecimal actualContribution() {
        return jdbc.sql("SELECT COALESCE(SUM(a.contribution), 0) FROM order_actuals a JOIN payments p ON p.order_id = a.order_id WHERE p.refunded_at IS NULL")
                .query(BigDecimal.class)
                .single();
    }

    public String campaigns() {
        return String.join("\n", jdbc.sql("""
                SELECT c.campaign, COUNT(DISTINCT c.id) AS starts, COUNT(p.order_id) AS paid
                FROM customers c LEFT JOIN orders o ON o.customer_id = c.id
                LEFT JOIN payments p ON p.order_id = o.id
                WHERE c.campaign IS NOT NULL GROUP BY c.campaign ORDER BY starts DESC LIMIT 10
                """)
                .query((rs, row) -> rs.getString("campaign") + ": запусков " + rs.getLong("starts")
                + ", оплаченных заказов " + rs.getLong("paid"))
                .list());
    }
}
