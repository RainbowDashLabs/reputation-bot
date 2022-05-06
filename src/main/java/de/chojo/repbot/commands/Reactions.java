package de.chojo.repbot.commands;

import de.chojo.jdautil.command.CommandMeta;
import de.chojo.jdautil.command.SimpleArgument;
import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.localization.util.LocalizedEmbedBuilder;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.util.Choice;
import de.chojo.jdautil.wrapper.SlashCommandContext;
import de.chojo.repbot.dao.access.guild.settings.Settings;
import de.chojo.repbot.dao.provider.Guilds;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.util.regex.Pattern;

public class Reactions extends SimpleCommand {
    private static final Pattern EMOTE_PATTERN = Pattern.compile("<a?:.*?:(?<id>[0-9]*?)>");
    private final Guilds guilds;

    public Reactions(Guilds guilds) {
        super(CommandMeta.builder("reactions", "command.reaction.description")
                .addSubCommand("main", "command.reaction.sub.main", argsBuilder()
                        .add(SimpleArgument.string("emote", "command.reaction.sub.main.arg.emote").asRequired()))
                .addSubCommand("add", "command.reaction.sub.add", argsBuilder()
                        .add(SimpleArgument.string("emote", "command.reaction.sub.add.arg.emote").asRequired().withAutoComplete()))
                .addSubCommand("remove", "command.reaction.sub.remove", argsBuilder()
                        .add(SimpleArgument.string("emote", "command.reaction.sub.remove.arg.emote")))
                .addSubCommand("info", "command.reaction.sub.info")
                .withPermission());
        this.guilds = guilds;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var cmd = event.getSubcommandName();

        if ("main".equalsIgnoreCase(cmd)) {
            reaction(event, context);
        }

        if ("add".equalsIgnoreCase(cmd)) {
            add(event, context);
        }
        if ("remove".equalsIgnoreCase(cmd)) {
            remove(event, context);
        }
        if ("info".equalsIgnoreCase(cmd)) {
            info(event, context);
        }
    }

    private void info(SlashCommandInteractionEvent event, SlashCommandContext context) {
        event.replyEmbeds(getInfoEmbed(guilds.guild(event.getGuild()).settings(), context)).queue();
    }

    private MessageEmbed getInfoEmbed(Settings settings, SlashCommandContext context) {
        var reactions = settings.thanking().reactions();
        var mainEmote = reactions.reactionMention();
        var emotes = String.join(" ", reactions.getAdditionalReactionMentions());

        return new LocalizedEmbedBuilder(context.localizer())
                .setTitle("command.reaction.sub.info.title")
                .addField("command.reaction.sub.info.main", mainEmote.orElse("words.unknown"), true)
                .addField("command.reaction.sub.info.additional", emotes, true)
                .build();
    }

    private void reaction(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var emote = event.getOption("emote").getAsString();
        var message = event.reply(context.localize("command.reaction.checking"))
                .flatMap(InteractionHook::retrieveOriginal).complete();
        handleSetCheckResult(event.getGuild(), context, message, emote);
    }

    private void add(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var emote = event.getOption("emote").getAsString();
        var message = event.reply(context.localize("command.reaction.checking"))
                .flatMap(InteractionHook::retrieveOriginal).complete();
        handleAddCheckResult(event.getGuild(), context, message, emote);
    }

    private void remove(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var reactions = guilds.guild(event.getGuild()).settings().thanking().reactions();
        var emote = event.getOption("emote").getAsString();
        var matcher = EMOTE_PATTERN.matcher(emote);
        if (matcher.find()) {
            if (reactions.remove(matcher.group("id"))) {
                event.reply(context.localize("command.reaction.sub.remove.removed")).queue();
                return;
            }
            event.reply(context.localize("command.reaction.sub.remove.notFound")).setEphemeral(true).queue();
            return;
        }

        if (reactions.remove(emote)) {
            event.reply(context.localize("command.reaction.sub.remove.removed")).queue();
            return;
        }
        event.reply(context.localize("command.reaction.sub.remove.notFound")).setEphemeral(true).queue();
    }

    private void handleSetCheckResult(Guild guild, SlashCommandContext context, Message message, String emote) {
        var reactions = guilds.guild(guild).settings().thanking().reactions();
        var result = checkEmoji(message, emote);
        switch (result.result) {
            case EMOJI_FOUND -> {
                if (reactions.mainReaction(emote)) {
                    message.editMessage(context.localize("command.reaction.sub.main.set",
                            Replacement.create("EMOTE", result.mention))).queue();
                }
            }
            case EMOTE_FOUND -> {
                if (reactions.mainReaction(result.id)) {
                    message.editMessage(context.localize("command.reaction.sub.main.set",
                            Replacement.create("EMOTE", result.mention))).queue();
                }
            }
            case NOT_FOUND -> message.editMessage(context.localize("command.reaction.sub.reaction.get.error")).queue();
            case UNKNOWN_EMOJI -> message.editMessage(context.localize("command.reaction.error.emojiNotFound")).queue();
        }
    }

    private void handleAddCheckResult(Guild guild, SlashCommandContext context, Message message, String emote) {
        var reactions = guilds.guild(guild).settings().thanking().reactions();
        var result = checkEmoji(message, emote);
        switch (result.result) {
            case EMOJI_FOUND -> {
                if (reactions.add(emote)) {
                    message.editMessage(context.localize("command.reaction.sub.add.add",
                            Replacement.create("EMOTE", result.mention))).queue();
                }
            }
            case EMOTE_FOUND -> {
                if (reactions.add(result.id)) {
                    message.editMessage(context.localize("command.reaction.sub.add.add",
                            Replacement.create("EMOTE", result.mention))).queue();
                }
            }
            case NOT_FOUND -> message.editMessage(context.localize("command.reaction.sub.reaction.get.error")).queue();
            case UNKNOWN_EMOJI -> message.editMessage(context.localize("command.reaction.error.emojiNotFound")).queue();
        }
    }

    private EmojiCheckResult checkEmoji(Message message, String emote) {
        var matcher = EMOTE_PATTERN.matcher(emote);
        if (!matcher.find()) {
            try {
                message.addReaction(emote).complete();
            } catch (ErrorResponseException e) {
                return new EmojiCheckResult(null, "", CheckResult.UNKNOWN_EMOJI);
            }
            return new EmojiCheckResult(emote, "", CheckResult.EMOJI_FOUND);
        }
        var id = matcher.group("id");
        var emoteById = message.getGuild().retrieveEmoteById(id).onErrorMap(err -> null).complete();
        if (emoteById == null) {
            return new EmojiCheckResult("", "", CheckResult.NOT_FOUND);
        }
        try {
            message.addReaction(emoteById).queue();
        } catch (IllegalArgumentException e) {
            return new EmojiCheckResult(null, "", CheckResult.NOT_FOUND);
        }
        return new EmojiCheckResult(emoteById.getAsMention(), emoteById.getId(), CheckResult.EMOTE_FOUND);
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event, SlashCommandContext slashCommandContext) {
        var react = guilds.guild(event.getGuild()).settings().thanking().reactions();
        if ("emote".equals(event.getFocusedOption().getName()) && "remove".equals(event.getSubcommandName())) {
            var reactions = react.reactions()
                    .stream()
                    .limit(25)
                    .map(Choice::toChoice)
                    .toList();
            event.replyChoices(reactions).queue();
        }
    }

    private enum CheckResult {
        EMOJI_FOUND, EMOTE_FOUND, NOT_FOUND, UNKNOWN_EMOJI
    }

    private static class EmojiCheckResult {
        private final String mention;
        private final String id;
        private final CheckResult result;

        public EmojiCheckResult(String mention, String id, CheckResult result) {
            this.mention = mention;
            this.id = id;
            this.result = result;
        }
    }
}
