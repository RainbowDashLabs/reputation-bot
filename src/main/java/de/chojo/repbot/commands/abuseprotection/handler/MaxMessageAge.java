package de.chojo.repbot.commands.abuseprotection.handler;

import de.chojo.jdautil.interactions.slash.structure.handler.SlashHandler;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.wrapper.EventContext;
import de.chojo.repbot.dao.access.guild.RepGuild;
import de.chojo.repbot.dao.provider.Guilds;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public class MaxMessageAge implements SlashHandler {
    private final Guilds guilds;

    public MaxMessageAge(Guilds guilds) {
        this.guilds = guilds;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, EventContext context) {
        RepGuild guild = guilds.guild(event.getGuild());
        var abuseSettings = guild.settings().abuseProtection();
        if (event.getOptions().isEmpty()) {
            event.reply(context.localize("command.abuseprotection.maxmessageage.get",
                    Replacement.create("MINUTES", abuseSettings.maxMessageAge()))).queue();
            return;
        }
        var age = event.getOption("minutes").getAsInt();

        age = Math.max(0, age);
        event.reply(context.localize("command.abuseprotection.maxmessageage.get",
                Replacement.create("MINUTES", abuseSettings.maxMessageAge(age)))).queue();
    }
}
