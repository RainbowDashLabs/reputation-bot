package de.chojo.repbot.dao.access.reputation;

import de.chojo.repbot.analyzer.ThankType;
import de.chojo.repbot.dao.components.GuildHolder;
import de.chojo.repbot.dao.components.MemberHolder;
import de.chojo.repbot.dao.snapshots.ReputationUser;
import de.chojo.sqlutil.base.QueryFactoryHolder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.slf4j.LoggerFactory.getLogger;

public class RepUser extends QueryFactoryHolder implements GuildHolder, MemberHolder {
    private static final Logger log = getLogger(RepUser.class);
    private Reputation reputation;
    private Member member;

    public RepUser(Reputation reputation, Member member) {
        super(reputation);
        this.reputation = reputation;
        this.member = member;
    }

    /**
     * Log reputation for a user.
     *
     * @param donor      donator of the reputation
     * @param message    message to log
     * @param refMessage reference message if available
     * @param type       type of reputation
     * @return true if the repuation was logged.
     */
    public boolean add(@Nullable User donor, @NotNull Message message, @Nullable Message refMessage, ThankType type) {
        var success = builder()
                              .query("""
                                      INSERT INTO
                                      reputation_log(guild_id, donor_id, receiver_id, message_id, ref_message_id, channel_id, cause) VALUES(?,?,?,?,?,?,?)
                                          ON CONFLICT(guild_id, donor_id, receiver_id, message_id)
                                              DO NOTHING;
                                      """)
                              .paramsBuilder(b -> b.setLong(guildId()).setLong(donor == null ? 0 : donor.getIdLong()).setLong(memberId())
                                      .setLong(message.getIdLong()).setLong(refMessage == null ? null : refMessage.getIdLong())
                                      .setLong(message.getChannel().getIdLong()).setString(type.name()))
                              .insert()
                              .executeSync() > 0;
        if (success) {
            log.debug("{} received one reputation from {} for message {}", member().getUser().getName(), donor != null ? donor.getName() : "unkown", message.getIdLong());
        }
        return success;
    }

    /**
     * Remove reputation of a type from a message.
     *
     * @param message message
     * @param type    type
     * @return true if at least one entry was removed
     */
    public boolean removeReputation(long message, ThankType type) {
        return builder()
                       .query("""
                               DELETE FROM
                                   reputation_log
                               WHERE
                                   message_id = ?
                                   AND donor_id = ?
                                   AND cause = ?;
                               """)
                       .paramsBuilder(stmt -> stmt.setLong(message).setLong(userId()).setString(type.name()))
                       .update()
                       .executeSync() > 0;
    }


    /**
     * Get the last time where the the user gave reputation or received reputation to or from this user
     *
     * @param other the other user
     * @return last timestamp as instant
     */
    public Optional<Instant> getLastReputation(User other) {
        return builder(Instant.class).
                query("""
                        SELECT
                            received
                        FROM
                            reputation_log
                        WHERE
                            guild_id = ?
                            AND ((donor_id = ? AND receiver_id = ?)
                                OR (donor_id = ? AND receiver_id = ?))
                        ORDER BY received DESC
                        LIMIT  1;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(reputation.guildId())
                        .setLong(memberId()).setLong(other.getIdLong()).
                        setLong(other.getIdLong()).setLong(memberId()))
                .readRow(row -> row.getTimestamp("received").toInstant())
                .firstSync();
    }

    /**
     * Get the time since the last reputation.
     *
     * @param other receiver
     * @return the time since the last vote in the requested time unit or  {@link Long#MAX_VALUE} if no entry was found.
     */
    public Optional<Duration> getLastRatedDuration(User other) {
        return getLastReputation(other).map(last -> Duration.between(last, Instant.now()));
    }

    /**
     * Get the reputation user.
     *
     * @return the reputation user
     */
    public Optional<ReputationUser> getReputation() {
        return builder(ReputationUser.class)
                .query("""
                        SELECT rank, user_id, reputation FROM user_reputation WHERE guild_id = ? AND user_id = ?;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guildId()).setLong(userId()))
                .readRow(ReputationUser::build)
                .firstSync();
    }



    @Override
    public Guild guild() {
        return reputation.guild();
    }

    @Override
    public Member member() {
        return member;
    }
}
