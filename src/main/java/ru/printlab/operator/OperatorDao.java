package ru.printlab.operator;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class OperatorDao {
    private final JdbcClient jdbc;

    public OperatorDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> accounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        var accounts = jdbc.sql("SELECT username, telegram_id FROM operator_accounts ORDER BY username")
                .query((rs, row) -> Map.entry(rs.getString("username"), rs.getLong("telegram_id")))
                .list();
        for (var account : accounts) {
            result.put(account.getKey(), account.getValue());
        }
        return result;
    }

    public void bind(String username, long user, Instant now) {
        jdbc.sql("INSERT INTO operator_accounts(username, telegram_id, bound_at) VALUES (?, ?, ?)")
                .param(username)
                .param(user)
                .param(Timestamp.from(now))
                .update();
    }

    public Long chat() {
        long id = jdbc.sql("SELECT COALESCE(telegram_chat_id, 0) FROM operator_chat WHERE id = 1")
                .query(Long.class)
                .single();
        if (id == 0) {
            return null;
        }
        return id;
    }

    public void chat(long id) {
        jdbc.sql("UPDATE operator_chat SET telegram_chat_id = ? WHERE id = 1")
                .param(id)
                .update();
    }
}
