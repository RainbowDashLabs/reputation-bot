package de.chojo.repbot.service;

import de.chojo.jdautil.localization.ILocalizer;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.parsing.Verifier;
import de.chojo.repbot.analyzer.ContextResolver;
import de.chojo.repbot.analyzer.MessageContext;
import de.chojo.repbot.analyzer.ThankType;
import de.chojo.repbot.config.elements.MagicImage;
import de.chojo.repbot.dao.access.guild.settings.Settings;
import de.chojo.repbot.dao.provider.Guilds;
import de.chojo.repbot.util.EmojiDebug;
import de.chojo.repbot.util.Messages;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class ReputationService {
    private final Guilds guilds;
    private final RoleAssigner assigner;
    private final MagicImage magicImage;
    private final ContextResolver contextResolver;
    private final ILocalizer localizer;
    private Instant lastEasterEggSent = Instant.EPOCH;

    public ReputationService(Guilds guilds, ContextResolver contextResolver, RoleAssigner assigner, MagicImage magicImage, ILocalizer localizer) {
        this.guilds = guilds;
        this.assigner = assigner;
        this.magicImage = magicImage;
        this.contextResolver = contextResolver;
        this.localizer = localizer;
    }

    /**
     * Submit a reputation.
     * <p>
     * This reputation will be checked by several factors based on the {@link de.chojo.repbot.dao.access.guild.settings.Settings}.
     *
     * @param guild      guild where the vote was given
     * @param donor      donor of the reputation
     * @param receiver   receiver of the reputation
     * @param message    triggered message
     * @param refMessage reference message if present
     * @param type       type of reputation source
     * @return true if the reputation was counted and is valid
     */
    public boolean submitReputation(Guild guild, Member donor, Member receiver, Message message, @Nullable Message refMessage, ThankType type) {
        // block bots
        if (receiver.getUser().isBot()) return false;

        var settings = guilds.guild(guild).settings();
        var messageSettings = settings.messages();
        var thankSettings = settings.thanking();
        var generalSettings = settings.general();
        var abuseSettings = settings.abuseProtection();

        // block non reputation channel
        if (!thankSettings.channels().isEnabled(message.getChannel())) return false;

        if (!thankSettings.donorRoles().hasRole(guild.getMember(donor))) return false;
        if (!thankSettings.receiverRoles().hasRole(guild.getMember(receiver))) return false;

        // force settings
        switch (type) {
            case FUZZY -> {
                if (!messageSettings.isFuzzyActive()) return false;
            }
            case MENTION -> {
                if (!messageSettings.isMentionActive()) return false;
            }
            case ANSWER -> {
                if (!messageSettings.isAnswerActive()) return false;
            }
            case REACTION -> {
                if (!messageSettings.isReactionActive()) return false;
            }
            case EMBED -> {
                if (!messageSettings.isEmbedActive()) return false;
            }
            default -> throw new IllegalStateException("Unexpected value: " + type);
        }

        MessageContext context;
        if (type == ThankType.REACTION) {
            // Check if user was recently seen in this channel.
            context = contextResolver.getCombinedContext(guild.getMember(donor), message, settings);
        } else {
            context = contextResolver.getCombinedContext(message, settings);
        }

        // Abuse Protection: target context
        if (!context.members().contains(receiver) && abuseSettings.isReceiverContext()) {
            if (generalSettings.isEmojiDebug()) Messages.markMessage(message, EmojiDebug.TARGET_NOT_IN_CONTEXT);
            return false;
        }

        // Abuse Protection: donor context
        if (!context.members().contains(donor) && abuseSettings.isDonorContext()) {
            if (generalSettings.isEmojiDebug()) Messages.markMessage(message, EmojiDebug.DONOR_NOT_IN_CONTEXT);
            return false;
        }

        // Abuse protection: Cooldown
        if (!canVote(donor, receiver, guild, settings)) {
            if (generalSettings.isEmojiDebug()) Messages.markMessage(message, EmojiDebug.ONLY_COOLDOWN);
            return false;
        }

        // block outdated ref message
        // Abuse protection: Message age
        if (refMessage != null) {
            if (abuseSettings.isOldMessage(refMessage) && !context.latestMessages(abuseSettings.minMessages()).contains(refMessage)) {
                if (generalSettings.isEmojiDebug()) Messages.markMessage(message, EmojiDebug.TOO_OLD);
                return false;
            }
        }

        // block outdated message
        // Abuse protection: Message age
        if (abuseSettings.isOldMessage(message)) {
            if (generalSettings.isEmojiDebug()) Messages.markMessage(message, EmojiDebug.TOO_OLD);
            return false;
        }

        // block self vote
        if (Verifier.equalSnowflake(receiver, donor)) {
            if (lastEasterEggSent.until(Instant.now(), ChronoUnit.MINUTES) > magicImage.magicImageCooldown()
                && ThreadLocalRandom.current().nextInt(magicImage.magicImagineChance()) == 0) {
                lastEasterEggSent = Instant.now();
                //TODO: Escape unknown channel 5
                message.replyEmbeds(new EmbedBuilder()
                                .setImage(magicImage.magicImageLink())
                                .setColor(Color.RED).build())
                        .queue(msg -> msg.delete().queueAfter(
                                magicImage.magicImageDeleteSchedule(), TimeUnit.SECONDS,
                                RestAction.getDefaultSuccess(),
                                ErrorResponseException.ignore(ErrorResponse.UNKNOWN_MESSAGE, ErrorResponse.UNKNOWN_CHANNEL))
                        );
            }
            return false;
        }

        // try to log reputation
        if (guilds.guild(guild).reputation().user(receiver).addReputation(donor, message, refMessage, type)) {
            // mark messages
            Messages.markMessage(message, refMessage, settings);
            // update role
            try {
                assigner.update(guild.getMember(receiver));
            } catch (RoleAccessException e) {
                message.getChannel()
                        .sendMessage(localizer.localize("error.roleAccess", message.getGuild(),
                                Replacement.createMention("ROLE", e.role())))
                        .allowedMentions(Collections.emptyList())
                        .queue();
            }
            return true;
        }
        // submit to database failed. Maybe this message was already voted by the user.
        return false;
    }

    public boolean canVote(Member donor, Member receiver, Guild guild, Settings settings) {
        var donorM = guild.getMember(donor);
        var receiverM = guild.getMember(receiver);

        if (donorM == null || receiverM == null) return false;

        // block cooldown
        var lastRated = guilds.guild(guild).reputation().user(donorM).getLastRatedDuration(receiver);
        if (lastRated.toMinutes() < settings.abuseProtection().cooldown()) return false;

        if (!settings.thanking().receiverRoles().hasRole(receiverM)) return false;
        if (!settings.thanking().donorRoles().hasRole(donorM)) return false;

        return lastRated.toMinutes() >= settings.abuseProtection().cooldown();
    }
}
