package ru.printlab.operator;

import org.springframework.stereotype.Service;
import ru.printlab.config.AppProperties;

import java.time.Clock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class OperatorAccess {
    private final OperatorDao dao;
    private final OperatorProperties config;
    private final AppProperties app;
    private final Clock clock;

    public OperatorAccess(OperatorDao dao, OperatorProperties config, AppProperties app, Clock clock) {
        this.dao = dao;
        this.config = config;
        this.app = app;
        this.clock = clock;
    }

    // Called only for authenticated Telegram updates, under the shared business lock.
    public void enroll(long id, String username) {
        String normalized = username.toLowerCase(Locale.ROOT);
        Map<String, Long> bound = dao.accounts();
        if (config.adminUsernames().contains(normalized) && !bound.containsKey(normalized)
                && !bound.containsValue(id)) {
            dao.bind(normalized, id, clock.instant());
        }
    }

    public boolean isAdmin(long id) {
        return admins().contains(id);
    }

    public Set<Long> admins() {
        Set<Long> result = new HashSet<>(app.adminIds());
        for (var account : dao.accounts().entrySet()) {
            if (config.adminUsernames().contains(account.getKey())) {
                result.add(account.getValue());
            }
        }
        return result;
    }

    public Set<Long> recipients() {
        Long group = dao.chat();
        if (group != null) {
            return Set.of(group);
        }
        return admins();
    }

    public void group(long id) {
        dao.chat(id);
    }

    public void migrate(long oldId, long newId) {
        Long current = dao.chat();
        if (current != null && current == oldId && newId < 0) {
            dao.chat(newId);
        }
    }
}
