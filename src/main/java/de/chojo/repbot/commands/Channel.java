package de.chojo.repbot.commands;

import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.localization.Localizer;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.parsing.DiscordResolver;
import de.chojo.jdautil.wrapper.CommandContext;
import de.chojo.jdautil.wrapper.MessageEventWrapper;
import de.chojo.repbot.data.GuildData;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.IMentionable;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class Channel extends SimpleCommand {
    private final GuildData data;
    private Localizer loc;

    public Channel(DataSource dataSource, Localizer localizer) {
        super("channel",
                null,
                "command.channel.description",
                null,
                subCommandBuilder()
                        .add("set", "<channel...>", "command.channel.sub.set")
                        .add("add", "<channel...>", "command.channel.sub.add")
                        .add("remove", "<channel...>", "command.channel.sub.remove")
                        .add("list", null, "command.channel.sub.list")
                        .build(),
                Permission.ADMINISTRATOR);
        data = new GuildData(dataSource);
        this.loc = localizer;
    }

    @Override
    public boolean onCommand(MessageEventWrapper messageEventWrapper, CommandContext commandContext) {
        var optsubCmd = commandContext.argString(0);
        if (optsubCmd.isEmpty()) return false;
        var subCmd = optsubCmd.get();

        if ("set".equalsIgnoreCase(subCmd)) {
            return set(messageEventWrapper, commandContext.subCommandcontext(subCmd));
        }
        if ("remove".equalsIgnoreCase(subCmd)) {
            return remove(messageEventWrapper, commandContext.subCommandcontext(subCmd));
        }
        if ("add".equalsIgnoreCase(subCmd)) {
            return add(messageEventWrapper, commandContext.subCommandcontext(subCmd));
        }
        if ("list".equalsIgnoreCase(subCmd)) {
            return list(messageEventWrapper, commandContext.subCommandcontext(subCmd));
        }
        return false;
    }

    private boolean add(MessageEventWrapper eventWrapper, CommandContext context) {
        if (context.argsEmpty()) return false;

        var args = context.args();
        var validTextChannels = DiscordResolver.getValidTextChannels(eventWrapper.getGuild(), args);
        if (validTextChannels.isEmpty()) {
            eventWrapper.replyErrorAndDelete(loc.localize("error.invalidChannel", eventWrapper), 10);
            return true;
        }

        var addedChannel = validTextChannels.stream()
                .filter(c -> data.addChannel(eventWrapper.getGuild(), c))
                .map(IMentionable::getAsMention)
                .collect(Collectors.joining(", "));
        eventWrapper.replyNonMention(
                loc.localize("command.channel.sub.add.added", eventWrapper,
                        Replacement.create("CHANNEL", addedChannel))).queue();
        return true;
    }

    private boolean remove(MessageEventWrapper eventWrapper, CommandContext context) {
        if (context.argsEmpty()) return false;

        var args = context.args();
        var validTextChannels = DiscordResolver.getValidTextChannels(eventWrapper.getGuild(), args);
        if (validTextChannels.isEmpty()) {
            eventWrapper.replyErrorAndDelete(loc.localize("error.invalidChannel", eventWrapper), 10);
            return true;
        }

        var removedChannel = validTextChannels.stream()
                .filter(c -> data.deleteChannel(eventWrapper.getGuild(), c))
                .map(IMentionable::getAsMention)
                .collect(Collectors.joining(", "));
        eventWrapper.replyNonMention(loc.localize("command.channel.sub.remove.removed", eventWrapper,
                Replacement.create("CHANNEL", removedChannel))).queue();
        return true;
    }

    private boolean list(MessageEventWrapper eventWrapper, CommandContext context) {
        var guildSettings = data.getGuildSettings(eventWrapper.getGuild());
        if (guildSettings.isEmpty()) return true;

        var settings = guildSettings.get();
        var channelNames = DiscordResolver
                .getValidTextChannelsById(
                        eventWrapper.getGuild(), new ArrayList<>(settings.getActiveChannel()))
                .stream().map(IMentionable::getAsMention).collect(Collectors.joining(", "));
        eventWrapper.replyNonMention(loc.localize("command.channel.sub.list.list", eventWrapper,
                Replacement.create("CHANNEL", channelNames))).queue();
        return true;
    }

    private boolean set(MessageEventWrapper eventWrapper, CommandContext context) {
        if (context.argsEmpty()) return false;

        var args = context.args();
        var validTextChannels = DiscordResolver.getValidTextChannels(eventWrapper.getGuild(), args);
        if (validTextChannels.isEmpty()) {
            eventWrapper.replyErrorAndDelete(loc.localize("error.invalidChannel", eventWrapper), 10);
            return true;
        }

        data.clearChannel(eventWrapper.getGuild());
        var collect = validTextChannels.stream()
                .filter(c -> data.addChannel(eventWrapper.getGuild(), c))
                .map(IMentionable::getAsMention)
                .collect(Collectors.joining(", "));
        eventWrapper.replyNonMention(loc.localize("command.channel.sub.set.set", eventWrapper,
                Replacement.create("CHANNEL", collect))).queue();
        return true;
    }
}