package de.chojo.repbot.data.wrapper;

import lombok.Getter;
import net.dv8tion.jda.api.entities.User;
import org.apache.commons.lang3.StringUtils;

@Getter
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

    public User userFromId() {
        return User.fromId(userId);
    }

    public String fancyString(int maxRank) {
        var length = String.valueOf(maxRank).length();
        var rank = StringUtils.rightPad(String.valueOf(this.rank), length);
        return "`" + rank + "` **|** " + userFromId().getAsMention() + " ➜ " + reputation;
    }
}
