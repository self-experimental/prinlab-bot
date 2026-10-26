package ru.printlab.telegram;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UpdateDao {
    private final JdbcClient jdbc;

    public UpdateDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long offset() {
        return jdbc.sql("SELECT next_update FROM bot_cursor WHERE id = 1")
                .query(Long.class)
                .single();
    }

    public long lockOffset() {
        return jdbc.sql("SELECT next_update FROM bot_cursor WHERE id = 1 FOR UPDATE")
                .query(Long.class)
                .single();
    }

    public void advance(long next) {
        jdbc.sql("UPDATE bot_cursor SET next_update = ? WHERE id = 1")
                .param(next)
                .update();
    }
}
