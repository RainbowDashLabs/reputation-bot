package de.chojo.repbot.commands.abuseprotection.handler;

import de.chojo.jdautil.interactions.slash.structure.handler.SlashHandler;
import de.chojo.jdautil.localization.util.LocalizedEmbedBuilder;
import de.chojo.jdautil.wrapper.EventContext;
import de.chojo.repbot.dao.access.guild.RepGuild;
import de.chojo.repbot.dao.provider.Guilds;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.jetbrains.annotations.PropertyKey;

import java.awt.Color;
import java.util.List;

public class Info implements SlashHandler {
    private final Guilds guilds;

    public Info(Guilds guilds) {
        this.guilds = guilds;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, EventContext context) {
        RepGuild guild = guilds.guild(event.getGuild());
        event.replyEmbeds(getSettings(context, guild)).queue();
    }

    private MessageEmbed getSettings(EventContext context, RepGuild guild) {
        var abuseProt = guild.settings().abuseProtection();
        var setting = List.of(
                getSetting("command.abuseprotection.info.embed.maxMessageAge", abuseProt.maxMessageAge()),
                getSetting("command.abuseprotection.info.embed.minMessages", abuseProt.minMessages()),
                getSetting("command.abuseprotection.info.embed.cooldown", abuseProt.cooldown()),
                getSetting("command.abuseprotection.info.embed.donorContext", abuseProt.isDonorContext()),
                getSetting("command.abuseprotection.info.embed.receiverContext", abuseProt.isReceiverContext()),
                getSetting("command.abuseprotection.info.embed.maxMessageRep", abuseProt.maxMessageReputation())
        );

        var settings = String.join("\n", setting);

        return new LocalizedEmbedBuilder(context.guildLocalizer())
                .setTitle("command.abuseprotection.info.embed.title")
                .appendDescription(settings)
                .setColor(Color.GREEN)
                .build();
    }

    private String getSetting(@PropertyKey(resourceBundle = "locale") String locale, Object object) {
        return String.format("$%s$: %s", locale, object);
    }

    private String getSetting(@PropertyKey(resourceBundle = "locale") String locale, boolean bool) {
        return String.format("$%s$: $%s$", locale, bool ? "words.enabled" : "words.disabled");
    }
}
