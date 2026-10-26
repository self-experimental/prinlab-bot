package ru.printlab.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.printlab.common.BusinessException;
import ru.printlab.common.Json;
import ru.printlab.pricing.PricingSnapshot;
import ru.printlab.storage.ModelFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OrderDao {
    private final JdbcClient jdbc;
    private final Json json;

    public OrderDao(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID customerId(long telegramId) {
        return jdbc.sql("SELECT id FROM customers WHERE telegram_id = ?")
                .param(telegramId)
                .query(UUID.class)
                .single();
    }

    public long telegramId(UUID customerId) {
        return jdbc.sql("SELECT telegram_id FROM customers WHERE id = ?")
                .param(customerId)
                .query(Long.class)
                .single();
    }

    private UUID actorId(long telegramId) {
        if (telegramId == 0) {
            return null;
        }
        return customerId(telegramId);
    }

    public void lock() {
        jdbc.sql("SELECT id FROM business_lock WHERE id = 1 FOR UPDATE")
                .query(Integer.class)
                .single();
    }

    public void register(long userId, String name, String campaign, Instant now) {
        long count = jdbc.sql("SELECT COUNT(*) FROM customers WHERE telegram_id = ?")
                .param(userId)
                .query(Long.class)
                .single();
        if (count == 0) {
            jdbc.sql("INSERT INTO customers(id, telegram_id, display_name, campaign, created_at) VALUES (?, ?, ?, ?, ?)")
                    .param(UUID.randomUUID())
                    .param(userId)
                    .param(name)
                    .param(campaign)
                    .param(Timestamp.from(now))
                    .update();
        }
    }

    public boolean consented(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM customers WHERE telegram_id = ? AND consent_at IS NOT NULL")
                .param(userId)
                .query(Long.class)
                .single() > 0;
    }

    public void consent(long userId, Instant now) {
        jdbc.sql("UPDATE customers SET consent_at = ? WHERE telegram_id = ?")
                .param(Timestamp.from(now))
                .param(userId)
                .update();
    }

    public PrintOrder create(long userId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO orders(id, customer_id, status, created_at) VALUES (?, ?, 'DRAFT', ?)")
                .param(id)
                .param(customerId(userId))
                .param(Timestamp.from(now))
                .update();
        return get(id);
    }

    public PrintOrder draft(long userId) {
        return jdbc.sql("SELECT * FROM orders WHERE customer_id = ? AND status = 'DRAFT' ORDER BY number DESC LIMIT 1")
                .param(customerId(userId))
                .query(this::mapOrder)
                .optional()
                .orElse(null);
    }

    public PrintOrder get(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = ?")
                .param(id)
                .query(this::mapOrder)
                .optional()
                .orElseThrow(() -> new BusinessException("Заказ не найден."));
    }

    public PrintOrder byNumber(long number) {
        return jdbc.sql("SELECT * FROM orders WHERE number = ?")
                .param(number)
                .query(this::mapOrder)
                .optional()
                .orElseThrow(() -> new BusinessException("Заказ не найден."));
    }

    public List<PrintOrder> list(long userId, boolean admin) {
        if (admin) {
            return jdbc.sql("SELECT * FROM orders ORDER BY number DESC LIMIT 15")
                    .query(this::mapOrder)
                    .list();
        }
        return jdbc.sql("SELECT * FROM orders WHERE customer_id = ? ORDER BY number DESC LIMIT 15")
                .param(customerId(userId))
                .query(this::mapOrder)
                .list();
    }

    public void model(UUID orderId, ModelFile model) {
        jdbc.sql("INSERT INTO model_files(id, customer_id, telegram_file_id, original_name, sha256, storage_name, size_bytes) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(model.id())
                .param(model.customerId())
                .param(model.telegramFileId())
                .param(model.name())
                .param(model.sha256())
                .param(model.storageName())
                .param(model.size())
                .update();
        jdbc.sql("UPDATE orders SET model_id = ? WHERE id = ?")
                .param(model.id())
                .param(orderId)
                .update();
    }

    public ModelFile model(UUID id) {
        return jdbc.sql("SELECT * FROM model_files WHERE id = ?")
                .param(id)
                .query((rs, row) -> new ModelFile(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getString("telegram_file_id"), rs.getString("original_name"), rs.getString("sha256"),
                        rs.getString("storage_name"), rs.getLong("size_bytes")))
                .single();
    }

    public void options(UUID id, String material, String profile, int infill, int quantity, String promo) {
        jdbc.sql("UPDATE orders SET material_id = ?, print_profile = ?, infill = ?, quantity = ?, promo_code = ? WHERE id = ?")
                .param(material)
                .param(profile)
                .param(infill)
                .param(quantity)
                .param(promo)
                .param(id)
                .update();
    }

    public void status(UUID id, OrderStatus status) {
        jdbc.sql("UPDATE orders SET status = ? WHERE id = ?")
                .param(status.name())
                .param(id)
                .update();
    }

    public long paidCount(UUID customerId) {
        return jdbc.sql("SELECT COUNT(*) FROM orders WHERE customer_id = ? AND paid_at IS NOT NULL")
                .param(customerId)
                .query(Long.class)
                .single();
    }

    public void saveQuote(Quote quote, OrderStatus status) {
        PricingSnapshot snapshot = quote.snapshot();
        jdbc.sql("INSERT INTO quotes(id, order_id, expires_at, snapshot, total, discount, contribution) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(quote.id())
                .param(quote.orderId())
                .param(Timestamp.from(quote.expiresAt()))
                .param(json.write(snapshot))
                .param(snapshot.publicPrice().total())
                .param(snapshot.publicPrice().discount())
                .param(snapshot.economics().contribution())
                .update();
        jdbc.sql("UPDATE orders SET quote_id = ?, status = ? WHERE id = ?")
                .param(quote.id())
                .param(status.name())
                .param(quote.orderId())
                .update();
    }

    public Quote quote(UUID id) {
        return jdbc.sql("SELECT * FROM quotes WHERE id = ?")
                .param(id)
                .query((rs, row) -> new Quote(
                rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant(), json.read(rs.getString("snapshot"), PricingSnapshot.class),
                rs.getObject("approved_by", UUID.class), rs.getString("approval_reason")))
                .optional()
                .orElseThrow(() -> new BusinessException("Предложение не найдено."));
    }

    public void approve(UUID quoteId, long adminId, String reason) {
        jdbc.sql("UPDATE quotes SET approved_by = ?, approval_reason = ? WHERE id = ? AND approved_by IS NULL")
                .param(customerId(adminId))
                .param(reason)
                .param(quoteId)
                .update();
    }

    public void event(UUID orderId, long actor, String action, String detail, Instant now) {
        jdbc.sql("INSERT INTO order_events(order_id, actor_id, action, detail, created_at) VALUES (?, ?, ?, ?, ?)")
                .param(orderId)
                .param(actorId(actor))
                .param(action)
                .param(detail)
                .param(Timestamp.from(now))
                .update();
    }

    public List<PrintOrder> expired(Instant now) {
        return jdbc.sql("""
                SELECT o.* FROM orders o JOIN quotes q ON q.id = o.quote_id
                WHERE o.status IN ('QUOTE_READY', 'REVIEW_REQUIRED', 'AWAITING_PAYMENT') AND q.expires_at <= ?
                """)
                .param(Timestamp.from(now))
                .query(this::mapOrder)
                .list();
    }

    private PrintOrder mapOrder(ResultSet rs, int row) throws SQLException {
        Instant paidAt = null;
        if (rs.getTimestamp("paid_at") != null) {
            paidAt = rs.getTimestamp("paid_at").toInstant();
        }
        return new PrintOrder(rs.getObject("id", UUID.class), rs.getLong("number"), rs.getObject("customer_id", UUID.class),
                OrderStatus.valueOf(rs.getString("status")), rs.getObject("model_id", UUID.class),
                rs.getString("material_id"), rs.getString("print_profile"), rs.getInt("infill"),
                rs.getInt("quantity"), rs.getString("promo_code"), rs.getObject("quote_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), paidAt);
    }
}
