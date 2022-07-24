package de.chojo.repbot.commands.abuseprotection.handler;

import de.chojo.jdautil.interactions.slash.structure.handler.SlashHandler;
import de.chojo.jdautil.wrapper.EventContext;
import de.chojo.repbot.dao.access.guild.RepGuild;
import de.chojo.repbot.dao.provider.Guilds;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public class DonorContext implements SlashHandler, BooleanMessageMapper {
    private final Guilds guilds;

    public DonorContext(Guilds guilds) {
        this.guilds = guilds;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, EventContext context) {
        RepGuild guild = guilds.guild(event.getGuild());
        var abuseSettings = guild.settings().abuseProtection();
        if (event.getOptions().isEmpty()) {
            event.reply(getBooleanMessage(context, abuseSettings.isDonorContext(),
                    "command.abuseprotection.donorcontext.true", "command.abuseprotection.donorcontext.false")).queue();
            return;
        }
        var state = event.getOption("state").getAsBoolean();

        event.reply(getBooleanMessage(context, abuseSettings.donorContext(state),
                "command.abuseprotection.donorcontext.true", "command.abuseprotection.donorcontext.false")).queue();

    }
}
