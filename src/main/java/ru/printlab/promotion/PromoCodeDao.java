package ru.printlab.promotion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class PromoCodeDao {
    private final JdbcClient jdbc;
    public PromoCodeDao(JdbcClient jdbc) { this.jdbc = jdbc; }

    public PromoCode get(UUID id) {
        return jdbc.sql("SELECT * FROM promo_codes WHERE id = ?").param(id).query(this::map).optional().orElse(null);
    }

    public PromoCode byCode(String code) {
        return jdbc.sql("SELECT * FROM promo_codes WHERE code = ?").param(code).query(this::map).optional().orElse(null);
    }

    public List<PromoCode> page(boolean deleted, int page) {
        return jdbc.sql("SELECT * FROM promo_codes WHERE deleted = ? ORDER BY created_at, code LIMIT 6 OFFSET ?")
                .param(deleted)
                .param(page * 5)
                .query(this::map)
                .list();
    }

    public void save(PromoCode p) {
        int updated = jdbc.sql("""
                UPDATE promo_codes SET code = ?, application_type = ?, percent = ?, starts_at = ?, expires_at = ?,
                enabled = ?, deleted = ?, failure_reason = ?, max_users = ?, max_orders_per_user = ?,
                campaign_group = ?, prerequisite_code = ?, prerequisite_paid_users = ? WHERE id = ?
                """)
                .param(p.code())
                .param(p.type().name())
                .param(p.percent())
                .param(Timestamp.from(p.startsAt()))
                .param(Timestamp.from(p.expiresAt()))
                .param(p.enabled())
                .param(p.deleted())
                .param(p.failureReason())
                .param(p.maxUsers())
                .param(p.maxOrdersPerUser())
                .param(p.campaignGroup())
                .param(p.prerequisiteCode())
                .param(p.prerequisitePaidUsers())
                .param(p.id())
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at,
                    enabled, deleted, failure_reason, max_users, max_orders_per_user, campaign_group, prerequisite_code, prerequisite_paid_users)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .param(p.id())
                    .param(p.code())
                    .param(p.type().name())
                    .param(p.percent())
                    .param(Timestamp.from(p.createdAt()))
                    .param(Timestamp.from(p.startsAt()))
                    .param(Timestamp.from(p.expiresAt()))
                    .param(p.enabled())
                    .param(p.deleted())
                    .param(p.failureReason())
                    .param(p.maxUsers())
                    .param(p.maxOrdersPerUser())
                    .param(p.campaignGroup())
                    .param(p.prerequisiteCode())
                    .param(p.prerequisitePaidUsers())
                    .update();
        }
    }

    private PromoCode map(ResultSet rs, int row) throws SQLException {
        return new PromoCode(rs.getObject("id", UUID.class), rs.getString("code"),
                PromoType.valueOf(rs.getString("application_type")), rs.getBigDecimal("percent"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("enabled"), rs.getBoolean("deleted"),
                rs.getString("failure_reason"), rs.getObject("max_users", Integer.class), rs.getInt("max_orders_per_user"),
                rs.getInt("used_users"), rs.getInt("used_orders"), rs.getString("campaign_group"),
                rs.getString("prerequisite_code"), rs.getInt("prerequisite_paid_users"));
    }
}
