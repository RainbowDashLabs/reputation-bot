package de.chojo.repbot.dao.access.guild.settings;

import de.chojo.repbot.dao.access.guild.RepGuild;
import de.chojo.repbot.dao.access.guild.settings.sub.AbuseProtection;
import de.chojo.repbot.dao.access.guild.settings.sub.General;
import de.chojo.repbot.dao.access.guild.settings.sub.Reputation;
import de.chojo.repbot.dao.access.guild.settings.sub.Ranks;
import de.chojo.repbot.dao.access.guild.settings.sub.Thanking;
import de.chojo.repbot.dao.components.GuildHolder;
import de.chojo.repbot.dao.pagination.Announcements;
import de.chojo.sqlutil.base.QueryFactoryHolder;
import net.dv8tion.jda.api.entities.Guild;

public class Settings extends QueryFactoryHolder implements GuildHolder {
    private final RepGuild repGuild;
    private AbuseProtection abuseProtection;
    private General general;
    private Reputation reputation;
    private Ranks ranks;
    private Thanking thanking;
    private Announcements announcements;

    public Settings(RepGuild repGuild) {
        super(repGuild);
        this.repGuild = repGuild;
    }

    public AbuseProtection abuseProtection() {
        if (abuseProtection != null) {
            return abuseProtection;
        }
        abuseProtection = builder(AbuseProtection.class)
                .query("""
                        SELECT
                            min_messages,
                            max_message_age,
                            receiver_context,
                            donor_context,
                            cooldown,
                            max_given,
                            max_given_hours,
                            max_received,
                            max_received_hours
                        FROM
                            abuse_protection
                        WHERE guild_id = ?;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guildId()))
                .readRow(rs -> AbuseProtection.build(this, rs))
                .firstSync()
                .orElseGet(() -> new AbuseProtection(this));
        return abuseProtection;
    }

    public Announcements announcements() {
        if (announcements != null) {
            return announcements;
        }
        announcements = builder(Announcements.class)
                .query("""
                        SELECT
                            active,
                            same_channel,
                            channel_id
                        FROM
                            announcements
                        WHERE guild_id = ?;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guildId()))
                .readRow(rs -> Announcements.build(this, rs))
                .firstSync()
                .orElseGet(() -> new Announcements(this));
        return announcements;
    }

    public Reputation reputation() {
        if (reputation != null) {
            return reputation;
        }
        reputation = builder(Reputation.class)
                .query("""
                        SELECT
                            reactions_active,
                            answer_active,
                            mention_active,
                            fuzzy_active,
                            embed_active
                        FROM
                            message_settings
                        WHERE guild_id = ?;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guildId()))
                .readRow(rs -> Reputation.build(this, rs))
                .firstSync()
                .orElseGet(() -> new Reputation(this));
        return reputation;
    }

    public General general() {
        if (general != null) {
            return general;
        }
        general = builder(General.class)
                .query("""
                        SELECT
                            language,
                            emoji_debug,
                            stack_roles
                        FROM
                            guild_settings
                        WHERE guild_id = ?;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guildId()))
                .readRow(rs -> General.build(this, rs))
                .firstSync()
                .orElseGet(() -> new General(this));
        return general;
    }

    public Thanking thanking() {
        if (thanking != null) {
            return thanking;
        }
        thanking = builder(Thanking.class)
                .query("""
                        SELECT
                            reaction,
                            reactions,
                            thankswords,
                            active_channels,
                            channel_whitelist,
                            receiver_roles,
                            donor_roles
                        FROM
                            get_thank_settings(?);
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guildId()))
                .readRow(rs -> Thanking.build(this, rs))
                .firstSync()
                .orElseGet(() -> new Thanking(this));
        return thanking;
    }

    public Ranks ranks() {
        if (ranks != null) {
            return ranks;
        }
        ranks = new Ranks(this, general().stackRoles());
        return ranks;
    }

    public RepGuild repGuild() {
        return repGuild;
    }

    @Override
    public Guild guild() {
        return repGuild.guild();
    }
}
