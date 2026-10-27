package ru.printlab.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class ColorDao {
    private final JdbcClient jdbc;

    public ColorDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FilamentColor> page(boolean customer, int page) {
        String filter = "deleted = FALSE";
        if (customer) {
            filter += " AND available = TRUE";
        }
        return jdbc.sql("SELECT * FROM filament_colors WHERE " + filter + " ORDER BY name, id LIMIT 6 OFFSET ?")
                .param(page * 5)
                .query(this::map)
                .list();
    }

    public FilamentColor get(UUID id) {
        return jdbc.sql("SELECT * FROM filament_colors WHERE id = ?")
                .param(id)
                .query(this::map)
                .optional()
                .orElse(null);
    }

    public void save(FilamentColor color) {
        int changed = jdbc.sql("UPDATE filament_colors SET name = ?, surface = ?, manufacturer = ?, palette_number = ?, bambu_article = ?, available = ?, deleted = ? WHERE id = ?")
                .param(color.name())
                .param(color.surface())
                .param(color.manufacturer())
                .param(color.paletteNumber())
                .param(color.bambuArticle())
                .param(color.available())
                .param(color.deleted())
                .param(color.id())
                .update();
        if (changed == 0) {
            jdbc.sql("INSERT INTO filament_colors(id, name, surface, manufacturer, palette_number, bambu_article, available, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .param(color.id())
                    .param(color.name())
                    .param(color.surface())
                    .param(color.manufacturer())
                    .param(color.paletteNumber())
                    .param(color.bambuArticle())
                    .param(color.available())
                    .param(color.deleted())
                    .update();
        }
    }

    private FilamentColor map(ResultSet rs, int row) throws SQLException {
        return new FilamentColor(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("surface"),
                rs.getString("manufacturer"), rs.getString("palette_number"), rs.getString("bambu_article"),
                rs.getBoolean("available"), rs.getBoolean("deleted"));
    }
}
