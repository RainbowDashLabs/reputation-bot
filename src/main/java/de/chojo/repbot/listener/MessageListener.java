package de.chojo.repbot.listener;

import de.chojo.jdautil.localization.Localizer;
import de.chojo.repbot.analyzer.MessageAnalyzer;
import de.chojo.repbot.analyzer.ThankType;
import de.chojo.repbot.config.Configuration;
import de.chojo.repbot.data.GuildData;
import de.chojo.repbot.data.ReputationData;
import de.chojo.repbot.data.wrapper.GuildSettings;
import de.chojo.repbot.listener.voting.ReputationVoteListener;
import de.chojo.repbot.manager.MemberCacheManager;
import de.chojo.repbot.manager.ReputationService;
import de.chojo.repbot.util.HistoryUtil;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class MessageListener extends ListenerAdapter {
    private static final Logger log = getLogger(MessageListener.class);
    private final Configuration configuration;
    private final GuildData guildData;
    private final ReputationData reputationData;
    private final MemberCacheManager memberCacheManager;
    private final String[] requestEmojis = new String[]{"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"};
    private final ReputationVoteListener reputationVoteListener;
    private final Localizer localizer;
    private final ReputationService reputationService;

    public MessageListener(DataSource dataSource, Configuration configuration, MemberCacheManager memberCacheManager, ReputationVoteListener reputationVoteListener, Localizer localizer, ReputationService reputationService) {
        guildData = new GuildData(dataSource);
        reputationData = new ReputationData(dataSource);
        this.configuration = configuration;
        this.memberCacheManager = memberCacheManager;
        this.reputationVoteListener = reputationVoteListener;
        this.localizer = localizer;
        this.reputationService = reputationService;
    }

    @Override
    public void onGuildMessageDelete(@NotNull GuildMessageDeleteEvent event) {
        reputationData.removeMessage(event.getMessageIdLong());
    }

    @Override
    public void onMessageBulkDelete(@NotNull MessageBulkDeleteEvent event) {
        event.getMessageIds().stream().map(Long::valueOf).forEach(reputationData::removeMessage);
    }

    @Override
    public void onGuildMessageReceived(@NotNull GuildMessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
        memberCacheManager.seen(event.getMember());
        var guild = event.getGuild();
        var optGuildSettings = guildData.getGuildSettings(guild);
        if (optGuildSettings.isEmpty()) return;
        var settings = optGuildSettings.get();

        if (!settings.isReputationChannel(event.getChannel())) return;

        var thankwordPattern = settings.thankwordPattern();

        var message = event.getMessage();

        var prefix = settings.prefix().orElse(configuration.defaultPrefix());
        if (prefix.startsWith("re:")) {
            var compile = Pattern.compile(prefix.substring(3));
            if (compile.matcher(message.getContentRaw()).find()) return;
        } else {
            if (message.getContentRaw().startsWith(prefix)) return;
        }
        if (message.getContentRaw().startsWith(settings.prefix().orElse(configuration.defaultPrefix()))) {
            return;
        }

        var analyzerResult = MessageAnalyzer.processMessage(thankwordPattern, message, settings.maxMessageAge(), true, 0.85, 3);

        var donator = analyzerResult.donator();

        if (analyzerResult.type() == ThankType.NO_MATCH) return;

        var resultType = analyzerResult.type();
        var resolveNoTarget = true;
        for (var result : analyzerResult.receivers()) {
            var refMessage = analyzerResult.referenceMessage();
            switch (resultType) {
                case FUZZY -> {
                    if (!settings.isFuzzyActive()) return;
                    reputationService.submitReputation(guild, donator, result.getReference().getUser(), message, refMessage, resultType);
                    resolveNoTarget = false;
                }
                case MENTION -> {
                    if (!settings.isMentionActive()) return;
                    reputationService.submitReputation(guild, donator, result.getReference().getUser(), message, refMessage, resultType);
                    resolveNoTarget = false;
                }
                case ANSWER -> {
                    if (!settings.isAnswerActive()) return;
                    if (!settings.isFreshMessage(refMessage)) return;
                    reputationService.submitReputation(guild, donator, result.getReference().getUser(), message, refMessage, resultType);
                    resolveNoTarget = false;
                }
            }
        }
        if (resolveNoTarget) resolveNoTarget(message, settings);
    }

    private void resolveNoTarget(Message message, GuildSettings settings) {
        var recentMembers = HistoryUtil.getRecentMembers(message, settings.maxMessageAge());
        recentMembers.remove(message.getMember());
        if (recentMembers.isEmpty()) return;

        var members = recentMembers.stream()
                .filter(receiver -> reputationService.canVote(message.getAuthor(), receiver.getUser(), message.getGuild(), settings))
                .limit(10)
                .collect(Collectors.toList());

        reputationVoteListener.registerVote(message, members);
    }
}
