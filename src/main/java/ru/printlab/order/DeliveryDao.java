package ru.printlab.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class DeliveryDao {
    private final JdbcClient jdbc;

    public DeliveryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DeliveryDetails get(UUID orderId) {
        return jdbc.sql("SELECT * FROM delivery_details WHERE order_id = ?")
                .param(orderId)
                .query((rs, row) -> new DeliveryDetails(orderId, rs.getString("method"), rs.getString("full_name"),
                        rs.getString("phone"), rs.getString("address"), rs.getObject("priced_quote_id", UUID.class)))
                .optional()
                .orElse(null);
    }

    public void save(DeliveryDetails delivery) {
        jdbc.sql("DELETE FROM delivery_details WHERE order_id = ?")
                .param(delivery.orderId())
                .update();
        jdbc.sql("INSERT INTO delivery_details(order_id, method, full_name, phone, address, priced_quote_id) VALUES (?, ?, ?, ?, ?, ?)")
                .param(delivery.orderId())
                .param(delivery.method())
                .param(delivery.fullName())
                .param(delivery.phone())
                .param(delivery.address())
                .param(delivery.pricedQuoteId())
                .update();
    }

    public void priced(UUID orderId, UUID quoteId) {
        jdbc.sql("UPDATE delivery_details SET priced_quote_id = ? WHERE order_id = ?")
                .param(quoteId)
                .param(orderId)
                .update();
    }
}
