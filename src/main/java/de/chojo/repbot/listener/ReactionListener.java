package de.chojo.repbot.listener;

import de.chojo.jdautil.parsing.Verifier;
import de.chojo.repbot.analyzer.ThankType;
import de.chojo.repbot.data.GuildData;
import de.chojo.repbot.data.ReputationData;
import de.chojo.repbot.manager.RoleAssigner;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ReactionListener extends ListenerAdapter {
    private final GuildData guildData;
    private final ReputationData reputationData;
    private final RoleAssigner roleAssigner;

    public ReactionListener(DataSource dataSource, RoleAssigner roleAssigner) {
        guildData = new GuildData(dataSource);
        reputationData = new ReputationData(dataSource);
        this.roleAssigner = roleAssigner;
    }

    @Override
    public void onGuildMessageReactionAdd(@NotNull GuildMessageReactionAddEvent event) {
        if (event.getUser().isBot()) return;
        var optGuildSettings = guildData.getGuildSettings(event.getGuild());
        if (optGuildSettings.isEmpty()) return;
        var guildSettings = optGuildSettings.get();
        if (!guildSettings.isReputationChannel(event.getChannel())) return;

        if (!guildSettings.isReactionActive()) return;

        if (!guildSettings.isReaction(event.getReaction().getReactionEmote())) return;

        event.getChannel()
                .retrieveMessageById(event.getMessageId())
                .timeout(10, TimeUnit.SECONDS)
                .queue(message -> {
                    var until = message.getTimeCreated().toInstant().until(Instant.now(), ChronoUnit.MINUTES);
                    if (until > guildSettings.getMaxMessageAge()) return;

                    var lastRatedDuration = reputationData.getLastRatedDuration(event.getGuild(), event.getUser(), message.getAuthor(), ChronoUnit.MINUTES);
                    if (lastRatedDuration < guildSettings.getCooldown()) return;

                    if (Verifier.equalSnowflake(event.getMember(), message.getAuthor())) return;

                    var logEntry = reputationData.getLogEntry(message);
                    if (logEntry.isPresent()) {
                        Member newReceiver;
                        try {
                            newReceiver = event.getGuild().retrieveMemberById(logEntry.get().getReceiverId()).complete();
                        } catch (RuntimeException e) {
                            return;
                        }
                        if (newReceiver == null) return;
                        var lastRated = reputationData.getLastRatedDuration(event.getGuild(), event.getUser(), newReceiver.getUser(), ChronoUnit.MINUTES);
                        if (lastRated < guildSettings.getCooldown()) return;
                        reputationData.logReputation(event.getGuild(), event.getUser(), newReceiver.getUser(), message, null, ThankType.REACTION);
                        return;
                    }

                    reputationData.logReputation(event.getGuild(), event.getUser(), message.getAuthor(), message, null, ThankType.REACTION);
                    roleAssigner.update(message.getMember());
                }, err -> {
                    log.error("Could not retrieve reaction message.", err);
                });

    }
}
