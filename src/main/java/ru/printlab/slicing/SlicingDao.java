package ru.printlab.slicing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class SlicingDao {
    private final JdbcClient jdbc;

    public SlicingDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID orderId, Instant now) {
        jdbc.sql("UPDATE slicing_jobs SET state = 'CANCELLED' WHERE order_id = ? AND state IN ('QUEUED', 'RUNNING')")
                .param(orderId)
                .update();
        jdbc.sql("INSERT INTO slicing_jobs(id, order_id, state, created_at) VALUES (?, ?, 'QUEUED', ?)")
                .param(UUID.randomUUID())
                .param(orderId)
                .param(Timestamp.from(now))
                .update();
    }

    public SliceJob claim(Instant now, int timeoutSeconds) {
        jdbc.sql("UPDATE slicing_jobs SET state = 'QUEUED' WHERE state = 'RUNNING' AND lease_until <= ?")
                .param(Timestamp.from(now))
                .update();
        SliceJob job = jdbc.sql("SELECT * FROM slicing_jobs WHERE state = 'QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE")
                .query((rs, row) -> new SliceJob(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getInt("attempts")))
                .optional()
                .orElse(null);
        if (job != null) {
            jdbc.sql("UPDATE slicing_jobs SET state = 'RUNNING', attempts = attempts + 1, lease_until = ? WHERE id = ?")
                    .param(Timestamp.from(now.plusSeconds(timeoutSeconds + 120L)))
                    .param(job.id())
                    .update();
        }
        return job;
    }

    public boolean running(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM slicing_jobs WHERE id = ? AND state = 'RUNNING'")
                .param(id)
                .query(Long.class)
                .single() > 0;
    }

    public void finish(UUID id, String state, String result) {
        jdbc.sql("UPDATE slicing_jobs SET state = ?, result = ? WHERE id = ?")
                .param(state)
                .param(result)
                .param(id)
                .update();
    }
}
