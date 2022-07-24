package de.chojo.repbot.commands.abuseprotection.handler;

import de.chojo.jdautil.interactions.slash.structure.handler.SlashHandler;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.wrapper.EventContext;
import de.chojo.repbot.dao.access.guild.RepGuild;
import de.chojo.repbot.dao.provider.Guilds;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public class ReceiverLimit implements SlashHandler {
    private final Guilds guilds;

    public ReceiverLimit(Guilds guilds) {
        this.guilds = guilds;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, EventContext context) {
        RepGuild guild = guilds.guild(event.getGuild());
        var protection = guild.settings().abuseProtection();
        var limit = event.getOption("limit");
        if (limit != null) {
            protection.maxReceived(limit.getAsInt());
        }

        var hours = event.getOption("hours");
        if (hours != null) {
            protection.maxReceivedHours(hours.getAsInt());
        }

        if (protection.maxReceived() == 0) {
            event.reply(context.localize("command.abuseprotection.receiverlimit.disabled")).setEphemeral(true).queue();
            return;
        }

        event.reply(context.localize("command.abuseprotection.receiverlimit.set",
                        Replacement.create("AMOUNT", protection.maxReceived()),
                        Replacement.create("HOURS", protection.maxReceivedHours())))
                .setEphemeral(true)
                .queue();
    }
}
