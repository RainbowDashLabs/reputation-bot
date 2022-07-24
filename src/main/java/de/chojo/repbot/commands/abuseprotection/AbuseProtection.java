package de.chojo.repbot.commands.abuseprotection;

import de.chojo.jdautil.interactions.slash.Argument;
import de.chojo.jdautil.interactions.slash.Slash;
import de.chojo.jdautil.interactions.slash.SubCommand;
import de.chojo.jdautil.interactions.slash.provider.SlashCommand;
import de.chojo.repbot.commands.abuseprotection.handler.Cooldown;
import de.chojo.repbot.commands.abuseprotection.handler.DonorContext;
import de.chojo.repbot.commands.abuseprotection.handler.DonorLimit;
import de.chojo.repbot.commands.abuseprotection.handler.Info;
import de.chojo.repbot.commands.abuseprotection.handler.MaxMessageAge;
import de.chojo.repbot.commands.abuseprotection.handler.MaxMessageReputation;
import de.chojo.repbot.commands.abuseprotection.handler.MinMessages;
import de.chojo.repbot.commands.abuseprotection.handler.ReceiverContext;
import de.chojo.repbot.commands.abuseprotection.handler.ReceiverLimit;
import de.chojo.repbot.dao.provider.Guilds;

public class AbuseProtection extends SlashCommand {
    public AbuseProtection(Guilds guilds) {
        super(Slash.of("abuseprotection", "command.abuseprotection.description")
                .adminCommand()
                .subCommand(SubCommand.of("info", "command.abuseprotection.info.description")
                        .handler(new Info(guilds)))
                .subCommand(SubCommand.of("maxmessageage", "command.abuseprotection.maxmessageage.description")
                        .handler(new MaxMessageAge(guilds))
                        .argument(Argument.integer("minutes", "command.abuseprotection.maxmessageage.minutes.description")))
                .subCommand(SubCommand.of("maxmessagereputation", "command.abuseprotection.maxmessagerep.description")
                        .handler(new MaxMessageReputation(guilds))
                        .argument(Argument.integer("amount", "command.abuseprotection.maxmessagerep.amount.description")))
                .subCommand(SubCommand.of("minmessages", "command.abuseprotection.minmessages.description")
                        .handler(new MinMessages(guilds))
                        .argument(Argument.integer("messages", "command.abuseprotection.minmessages.messages.description")))
                .subCommand(SubCommand.of("cooldown", "command.abuseprotection.cooldown.description")
                        .handler(new Cooldown(guilds))
                        .argument(Argument.integer("minutes", "command.abuseprotection.cooldown.minutes.description")))
                .subCommand(SubCommand.of("donorcontext", "command.abuseprotection.donorcontext.description")
                        .handler(new DonorContext(guilds))
                        .argument(Argument.bool("state", "command.abuseprotection.donorcontext.state.description")))
                .subCommand(SubCommand.of("receivercontext", "command.abuseprotection.receivercontext.description")
                        .handler(new ReceiverContext(guilds))
                        .argument(Argument.bool("state", "command.abuseprotection.receivercontext.state.description")))
                .subCommand(SubCommand.of("donorlimit", "command.abuseprotection.donorlimit.description")
                        .handler(new DonorLimit(guilds))
                        .argument(Argument.integer("limit", "command.abuseprotection.donorlimit.limit.description"))
                        .argument(Argument.integer("hours", "command.abuseprotection.donorlimit.hours.description")))
                .subCommand(SubCommand.of("receiverlimit", "command.abuseprotection.receiverLimit.description")
                        .handler(new ReceiverLimit(guilds))
                        .argument(Argument.integer("limit", "command.abuseprotection.receiverlimit.limit.description"))
                        .argument(Argument.integer("hours", "command.abuseprotection.receiverlimit.hours.description")))
        );
    }
}
