package de.chojo.repbot.dao.snapshots.statistics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

public record CountStatistics(LocalDate date, int count) {

    public static CountStatistics build(ResultSet rs, String dateKey) throws SQLException {
        return new CountStatistics(rs.getDate(dateKey).toLocalDate(),
                rs.getInt("count"));
    }
}
