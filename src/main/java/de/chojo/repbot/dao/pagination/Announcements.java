package de.chojo.repbot.dao.pagination;

import de.chojo.jdautil.consumer.ThrowingConsumer;
import de.chojo.repbot.dao.access.guild.settings.Settings;
import de.chojo.repbot.dao.components.GuildHolder;
import de.chojo.sqlutil.base.QueryFactoryHolder;
import de.chojo.sqlutil.wrapper.ParamBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.TextChannel;

import java.sql.ResultSet;
import java.sql.SQLException;

public class Announcements extends QueryFactoryHolder implements GuildHolder {
    private boolean active = false;
    private boolean sameChannel = true;
    private long channelId = 0;
    private final Settings settings;

    private Announcements(Settings settings, boolean active, boolean sameChannel, long channelId) {
        super(settings);
        this.settings = settings;
        this.active = active;
        this.sameChannel = sameChannel;
        this.channelId = channelId;
    }

    public Announcements(Settings settings) {
        super(settings);
        this.settings = settings;
    }

    public static Announcements build(Settings settings, ResultSet rs) throws SQLException {
        return new Announcements(settings,
                rs.getBoolean("active"),
                rs.getBoolean("same_channel"),
                rs.getLong("channel_id"));
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSameChannel() {
        return sameChannel;
    }

    public long channelId() {
        return channelId;
    }

    public boolean active(boolean active) {
        if (set("active", stmt -> stmt.setBoolean(active))) {
            this.active = active;
        }
        return this.active;
    }

    public boolean sameChannel(boolean sameChannel) {
        if (set("same_channel", stmt -> stmt.setBoolean(sameChannel))) {
            this.sameChannel = sameChannel;
        }
        return this.sameChannel;
    }

    public long channel(TextChannel textChannel) {
        if (set("channel_id", stmt -> stmt.setLong(textChannel.getIdLong()))) {
            channelId = textChannel.getIdLong();
        }
        return channelId;
    }

    private boolean set(String parameter, ThrowingConsumer<ParamBuilder, SQLException> builder) {
        return builder()
                       .query("""
                               INSERT INTO announcements(guild_id, %s) VALUES (?, ?)
                               ON CONFLICT(guild_id)
                                   DO UPDATE SET %s = excluded.%s;
                               """, parameter, parameter, parameter)
                       .paramsBuilder(stmts -> {
                           stmts.setLong(guildId());
                           builder.accept(stmts);
                       }).insert()
                       .executeSync() > 0;
    }

    @Override
    public Guild guild() {
        return settings.guild();
    }
}
