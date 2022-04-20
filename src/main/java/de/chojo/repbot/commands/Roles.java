package de.chojo.repbot.commands;

import de.chojo.jdautil.command.CommandMeta;
import de.chojo.jdautil.command.SimpleArgument;
import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.localization.util.LocalizedEmbedBuilder;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.util.Futures;
import de.chojo.jdautil.wrapper.SlashCommandContext;
import de.chojo.repbot.data.GuildData;
import de.chojo.repbot.data.wrapper.GuildSettings;
import de.chojo.repbot.service.RoleAccessException;
import de.chojo.repbot.service.RoleAssigner;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class Roles extends SimpleCommand {
    private static final Logger log = getLogger(Roles.class);
    private final GuildData guildData;
    private final RoleAssigner roleAssigner;
    private final Set<Long> running = new HashSet<>();

    public Roles(DataSource dataSource, RoleAssigner roleAssigner) {
        super(CommandMeta.builder("roles", "command.roles.description")
                .addSubCommand("managerrole", "command.roles.sub.managerRole", argsBuilder()
                        .add(SimpleArgument.role("role", "command.roles.sub.managerRole.arg.role")))
                .addSubCommand("add", "command.roles.sub.add", argsBuilder()
                        .add(SimpleArgument.role("role", "command.roles.sub.add.arg.role").asRequired())
                        .add(SimpleArgument.integer("reputation", "command.roles.sub.add.arg.reputation").asRequired()))
                .addSubCommand("remove", "command.roles.sub.remove", argsBuilder()
                        .add(SimpleArgument.role("role", "command.roles.sub.remove.arg.role").asRequired()))
                .addSubCommand("adddonor", "command.roles.sub.addDonor", argsBuilder()
                        .add(SimpleArgument.role("role", "command.roles.sub.addDonor.arg.role").asRequired()))
                .addSubCommand("addreceiver", "command.roles.sub.addReceiver", argsBuilder()
                        .add(SimpleArgument.role("role", "command.roles.sub.addReceiver.arg.role").asRequired()))
                .addSubCommand("removedonor", "command.roles.sub.removeDonor", argsBuilder()
                        .add(SimpleArgument.role("role", "command.roles.sub.removeDonor.arg.role").asRequired()))
                .addSubCommand("removereceiver", "command.roles.sub.removeReceiver", argsBuilder()
                        .add(SimpleArgument.role("role", "command.roles.sub.removeReceiver.arg.role").asRequired()))
                .addSubCommand("refresh", "command.roles.sub.refresh")
                .addSubCommand("list", "command.roles.sub.list")
                .addSubCommand("stackroles", "command.roles.sub.stackRoles", argsBuilder()
                        .add(SimpleArgument.bool("stack", "command.roles.sub.stackRoles.arg.stack")))
                .withPermission());
        guildData = new GuildData(dataSource);
        this.roleAssigner = roleAssigner;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var subCmd = event.getSubcommandName();
        if ("list".equalsIgnoreCase(subCmd)) {
            list(event, context);
        }

        if ("add".equalsIgnoreCase(subCmd)) {
            add(event, context);
        }

        if ("remove".equalsIgnoreCase(subCmd)) {
            remove(event, context);
        }

        if ("managerRole".equalsIgnoreCase(subCmd)) {
            managerRole(event, context);
        }

        if ("addDonor".equalsIgnoreCase(subCmd)) {
            addDonor(event, context, event.getOption("role").getAsRole());
        }

        if ("addReceiver".equalsIgnoreCase(subCmd)) {
            addReceiver(event, context, event.getOption("role").getAsRole());
        }

        if ("removeDonor".equalsIgnoreCase(subCmd)) {
            removeDonor(event, context, event.getOption("role").getAsRole());
        }

        if ("removeReceiver".equalsIgnoreCase(subCmd)) {
            removeReceiver(event, context, event.getOption("role").getAsRole());
        }

        if ("refresh".equalsIgnoreCase(subCmd)) {
            refresh(event, context);
        }

        if ("stackRoles".equalsIgnoreCase(subCmd)) {
            stackRoles(event, context);
        }
    }

    private void refresh(SlashCommandInteractionEvent event, SlashCommandContext context) {
        if (running.contains(event.getGuild().getIdLong())) {
            event.reply(context.localize("command.roles.sub.refresh.running")).queue();
            return;
        }

        running.add(event.getGuild().getIdLong());

        event.reply(context.localize("command.roles.sub.refresh.started")).queue();
        var start = Instant.now();
        roleAssigner
                .updateBatch(event.getGuild())
                .whenComplete(Futures.whenComplete(res -> {
                    var duration = DurationFormatUtils.formatDuration(start.until(Instant.now(), ChronoUnit.MILLIS), "mm:ss");
                    log.debug("Update of roles took: {}.", duration);
                    if (event.getHook().isExpired()) {
                        log.debug("Interaction hook is expired. Using fallback message.");
                        event.getChannel()
                                .sendMessage(context.localize("command.roles.sub.refresh.finished"))
                                .queue();
                        return;
                    }
                    event.getHook()
                            .editOriginal(context.localize("command.roles.sub.refresh.finished"))
                            .queue();
                }, err -> {
                    if (err instanceof RoleAccessException roleException) {
                        event.getHook()
                                .editOriginal(context.localize("error.roleAccess",
                                        Replacement.createMention("ROLE", roleException.role())))
                                .queue();
                    }
                }))
                .thenRun(() -> running.remove(event.getGuild().getIdLong()));
    }

    private void stackRoles(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var settings = guildData.getGuildSettings(event.getGuild());
        if (event.getOptions().isEmpty()) {
            event.reply(getBooleanMessage(context, settings.generalSettings().isStackRoles(),
                    "command.roles.sub.stackRoles.stacked", "command.roles.sub.stackRoles.notStacked")).queue();
            return;
        }
        var state = event.getOption("stack").getAsBoolean();

        if (guildData.setRoleStacking(event.getGuild(), state)) {
            event.reply(getBooleanMessage(context, state,
                    "command.roles.sub.stackRoles.stacked", "command.roles.sub.stackRoles.notStacked")).queue();
        }
    }

    private void managerRole(SlashCommandInteractionEvent event, SlashCommandContext context) {
        if (event.getOptions().isEmpty()) {
            var settings = guildData.getGuildSettings(event.getGuild());
            event.reply(getManagerRoleMessage(context, event.getGuild(), settings)).allowedMentions(Collections.emptyList()).queue();
            return;
        }
        var role = event.getOption("role").getAsRole();
        if (guildData.setManagerRole(event.getGuild(), role)) {
            event.reply(context.localize("command.roles.sub.managerRole.set",
                            Replacement.createMention(role)))
                    .allowedMentions(Collections.emptyList()).queue();

            context.commandHub().buildGuildPrivileges(event.getGuild());
        }
    }

    private String getManagerRoleMessage(SlashCommandContext context, Guild guild, GuildSettings settings) {
        if (settings.generalSettings().managerRole().isPresent()) {
            var roleById = guild.getRoleById(settings.generalSettings().managerRole().get());
            if (roleById != null) {
                return context.localize("command.roles.sub.managerRole.current",
                        Replacement.createMention(roleById));
            }
        }
        return context.localize("command.roles.sub.managerRole.noRole");
    }

    private void remove(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var role = event.getOption("role").getAsRole();

        if (guildData.removeReputationRole(event.getGuild(), role)) {
            event.reply(context.localize("command.roles.sub.remove.removed",
                    Replacement.createMention("ROLE", role))).allowedMentions(Collections.emptyList()).queue();
            return;
        }
        event.reply(context.localize("command.roles.sub.remove.notARepRole")).setEphemeral(true).queue();
    }

    private void add(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var role = event.getOption("role").getAsRole();
        var reputation = event.getOption("reputation").getAsLong();
        if (!event.getGuild().getSelfMember().canInteract(role)) {
            event.reply(context.localize("error.roleAccess",
                    Replacement.createMention(role))).setEphemeral(true).queue();
            return;
        }

        if (guildData.addReputationRole(event.getGuild(), role, reputation)) {
            event.reply(context.localize("command.roles.sub.add.added",
                            Replacement.createMention("ROLE", role), Replacement.create("POINTS", reputation)))
                    .allowedMentions(Collections.emptyList()).queue();
        }
    }

    private void list(SlashCommandInteractionEvent event, SlashCommandContext context) {
        event.replyEmbeds(getRoleList(context, event.getGuild())).allowedMentions(Collections.emptyList()).queue();
    }

    private MessageEmbed getRoleList(SlashCommandContext context, Guild guild) {
        var reputationRoles = guildData.getReputationRoles(guild).stream()
                .filter(role -> role.getRole(guild) != null)
                .map(role -> role.reputation() + " ➜ " + role.getRole(guild).getAsMention())
                .collect(Collectors.joining("\n"));
        var guildSettings = guildData.getGuildSettings(guild);

        var builder = new LocalizedEmbedBuilder(context.localizer())
                .setTitle("Role Info");

        builder.addField("Reputation Roles", reputationRoles, true);

        var thankSettings = guildSettings.thankSettings();

        if (!thankSettings.donorRoles().isEmpty()) {
            var donorRoles = thankSettings.donorRoles()
                    .stream()
                    .map(guild::getRoleById)
                    .filter(Objects::nonNull)
                    .map(IMentionable::getAsMention)
                    .collect(Collectors.joining("\n"));

            builder.addField("Donor Roles", donorRoles, true);
        }
        if (!thankSettings.receiverRoles().isEmpty()) {
            var receiverRoles = thankSettings.receiverRoles()
                    .stream()
                    .map(guild::getRoleById)
                    .filter(Objects::nonNull)
                    .map(IMentionable::getAsMention)
                    .collect(Collectors.joining("\n"));

            builder.addField("Receiver Roles", receiverRoles, true);
        }
        return builder.build();
    }

    private void addDonor(SlashCommandInteractionEvent event, SlashCommandContext context, Role role) {
        guildData.addDonorRole(event.getGuild(), role);
        event.reply(context.localize("command.roles.sub.addDonor.add",
                Replacement.createMention(role))).allowedMentions(Collections.emptyList()).queue();
    }

    private void addReceiver(SlashCommandInteractionEvent event, SlashCommandContext context, Role role) {
        guildData.addReceiverRole(event.getGuild(), role);
        event.reply(context.localize("command.roles.sub.addReceiver.add",
                Replacement.createMention(role))).allowedMentions(Collections.emptyList()).queue();
    }

    private void removeDonor(SlashCommandInteractionEvent event, SlashCommandContext context, Role role) {
        guildData.removeDonorRole(event.getGuild(), role);
        event.reply(context.localize("command.roles.sub.removeDonor.remove",
                Replacement.createMention(role))).allowedMentions(Collections.emptyList()).queue();
    }

    private void removeReceiver(SlashCommandInteractionEvent event, SlashCommandContext context, Role role) {
        guildData.removeReceiverRole(event.getGuild(), role);
        event.reply(context.localize("command.roles.sub.removeReceiver.remove",
                Replacement.createMention(role))).allowedMentions(Collections.emptyList()).queue();
    }

    private String getBooleanMessage(SlashCommandContext context, boolean value, String whenTrue, String whenFalse) {
        return context.localize(value ? whenTrue : whenFalse);
    }
}
