package de.chojo.repbot.dao.snapshots;

import de.chojo.jdautil.util.MentionUtil;
import net.dv8tion.jda.api.entities.User;
import org.apache.commons.lang3.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ReputationUser {
    private final long rank;
    private final Long userId;
    private final long reputation;

    public ReputationUser(long rank, Long userId, long reputation) {
        this.rank = rank;
        this.userId = userId;
        this.reputation = reputation;
    }

    public static ReputationUser empty(User user) {
        return new ReputationUser(0, user.getIdLong(), 0);
    }

    public static ReputationUser build(ResultSet rs) throws SQLException {
        return new ReputationUser(
                rs.getLong("rank"),
                rs.getLong("user_id"),
                rs.getLong("reputation")
        );
    }

    public String fancyString(int maxRank) {
        var length = String.valueOf(maxRank).length();
        var rank = StringUtils.rightPad(String.valueOf(this.rank), length);
        return "`" + rank + "` **|** " + MentionUtil.user(userId) + " ➜ " + reputation;
    }

    public long rank() {
        return rank;
    }

    public Long userId() {
        return userId;
    }

    public long reputation() {
        return reputation;
    }
}