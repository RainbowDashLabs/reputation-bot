package de.chojo.repbot.listener;

import de.chojo.jdautil.localization.ILocalizer;
import de.chojo.repbot.analyzer.ContextResolver;
import de.chojo.repbot.analyzer.MessageAnalyzer;
import de.chojo.repbot.analyzer.ThankType;
import de.chojo.repbot.config.Configuration;
import de.chojo.repbot.dao.access.guild.settings.Settings;
import de.chojo.repbot.dao.access.guild.settings.sub.Thanking;
import de.chojo.repbot.dao.provider.Guilds;
import de.chojo.repbot.dao.snapshots.ReputationLogEntry;
import de.chojo.repbot.listener.voting.ReputationVoteListener;
import de.chojo.repbot.service.RepBotCachePolicy;
import de.chojo.repbot.service.ReputationService;
import de.chojo.repbot.util.EmojiDebug;
import de.chojo.repbot.util.Messages;
import de.chojo.repbot.util.PermissionErrorHandler;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.ChannelType;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.ThreadChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class MessageListener extends ListenerAdapter {
    private static final Logger log = getLogger(MessageListener.class);
    private final ILocalizer localizer;
    private final Configuration configuration;
    private final Guilds guilds;
    private final RepBotCachePolicy repBotCachePolicy;
    private final ReputationVoteListener reputationVoteListener;
    private final ReputationService reputationService;
    private final ContextResolver contextResolver;
    private final MessageAnalyzer messageAnalyzer;

    public MessageListener(ILocalizer localizer, Configuration configuration, Guilds guilds, RepBotCachePolicy repBotCachePolicy,
                           ReputationVoteListener reputationVoteListener, ReputationService reputationService,
                           ContextResolver contextResolver, MessageAnalyzer messageAnalyzer) {
        this.localizer = localizer;
        this.guilds = guilds;
        this.configuration = configuration;
        this.repBotCachePolicy = repBotCachePolicy;
        this.reputationVoteListener = reputationVoteListener;
        this.reputationService = reputationService;
        this.contextResolver = contextResolver;
        this.messageAnalyzer = messageAnalyzer;
    }

    @Override
    public void onChannelCreate(@NotNull ChannelCreateEvent event) {
        if (event.getChannelType() == ChannelType.GUILD_PUBLIC_THREAD) {
            var thread = ((ThreadChannel) event.getChannel());
            var settings = guilds.guild(event.getGuild()).settings();
            if (settings.thanking().channels().isEnabled(thread)) {
                thread.join().queue();
            }
        }
    }

    @Override
    public void onMessageDelete(@NotNull MessageDeleteEvent event) {
        guilds.guild(event.getGuild()).reputation().log()
                .getLogEntry(event.getMessageIdLong())
                .ifPresent(ReputationLogEntry::deleteAll);
    }

    @Override
    public void onMessageBulkDelete(@NotNull MessageBulkDeleteEvent event) {
        var reputationLog = guilds.guild(event.getGuild()).reputation().log();
        event.getMessageIds().stream().map(Long::valueOf)
                .map(reputationLog::getLogEntry)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(ReputationLogEntry::deleteAll);
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage() || !event.isFromGuild()) return;
        var guild = event.getGuild();
        var repGuild = guilds.guild(guild);
        var settings = repGuild.settings();
        Thanking thank = settings.thanking();

        if (event.getMessage().getType() != MessageType.DEFAULT && event.getMessage().getType() != MessageType.INLINE_REPLY) {
            return;
        }

        if (!thank.channels().isEnabled(event.getGuildChannel())) return;
        repBotCachePolicy.seen(event.getMember());

        if (!thank.donorRoles().hasRole(event.getMember())) return;

        var message = event.getMessage();

        var analyzerResult = messageAnalyzer.processMessage(thank.thankwords().thankwordPattern(), message, settings, true, settings.abuseProtection().maxMessageReputation());

        if (analyzerResult.type() == ThankType.NO_MATCH) return;

        if (PermissionErrorHandler.assertAndHandle(event.getGuildChannel(), localizer, configuration,
                Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_EMBED_LINKS)) {
            return;
        }

        if (settings.general().isEmojiDebug()) {
            Messages.markMessage(event.getMessage(), EmojiDebug.FOUND_THANKWORD);
        }

        if (settings.abuseProtection().isDonorLimit(event.getMember())) {
            if (settings.general().isEmojiDebug()) {
                Messages.markMessage(event.getMessage(), EmojiDebug.DONOR_LIMIT);
            }
            return;
        }

        var resultType = analyzerResult.type();
        var resolveNoTarget = true;

        var donator = analyzerResult.donator();

        for (var result : analyzerResult.receivers()) {
            var refMessage = analyzerResult.referenceMessage();
            switch (resultType) {
                case FUZZY -> {
                    if (!settings.reputation().isFuzzyActive()) continue;
                    reputationService.submitReputation(guild, donator, result.getReference(), message, refMessage, resultType);
                    resolveNoTarget = false;
                }
                case MENTION -> {
                    if (!settings.reputation().isMentionActive()) continue;
                    reputationService.submitReputation(guild, donator, result.getReference(), message, refMessage, resultType);
                    resolveNoTarget = false;
                }
                case ANSWER -> {
                    if (!settings.reputation().isAnswerActive()) continue;
                    reputationService.submitReputation(guild, donator, result.getReference(), message, refMessage, resultType);
                    resolveNoTarget = false;
                }
            }
        }
        if (resolveNoTarget && settings.reputation().isEmbedActive()) resolveNoTarget(message, settings);
    }

    private void resolveNoTarget(Message message, Settings settings) {
        var recentMembers = new LinkedHashSet<>(contextResolver.getCombinedContext(message, settings).members());
        recentMembers.remove(message.getMember());

        if (recentMembers.isEmpty()) {
            if (settings.general().isEmojiDebug()) Messages.markMessage(message, EmojiDebug.EMPTY_CONTEXT);
            return;
        }

        var members = recentMembers.stream()
                .filter(receiver -> reputationService.canVote(message.getMember(), receiver, message.getGuild(), settings))
                .filter(receiver -> !settings.abuseProtection().isReceiverLimit(receiver))
                .limit(10)
                .collect(Collectors.toList());

        if (members.isEmpty()) {
            if (settings.general().isEmojiDebug()) Messages.markMessage(message, EmojiDebug.ONLY_COOLDOWN);
            return;
        }

        if(members.size() == 1 && settings.reputation().isSkipSingleEmbed()){
            reputationService.submitReputation(message.getGuild(), message.getMember(), members.get(0), message, null, ThankType.DIRECT);
            return;
        }

        reputationVoteListener.registerVote(message, members, settings);
    }
}
