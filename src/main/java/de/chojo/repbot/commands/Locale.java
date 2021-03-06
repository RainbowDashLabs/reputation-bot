package de.chojo.repbot.commands;

import de.chojo.jdautil.command.CommandMeta;
import de.chojo.jdautil.command.SimpleArgument;
import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.localization.util.Format;
import de.chojo.jdautil.localization.util.Language;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.text.TextFormatting;
import de.chojo.jdautil.util.Completion;
import de.chojo.jdautil.wrapper.SlashCommandContext;
import de.chojo.repbot.dao.provider.Guilds;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Locale extends SimpleCommand {
    private final Guilds guilds;
    private final ScheduledExecutorService executorService;

    public Locale(Guilds guilds, ScheduledExecutorService executorService) {
        super(CommandMeta.builder("locale", "command.locale.description")
                .addSubCommand("set", "command.locale.sub.set", argsBuilder()
                        .add(SimpleArgument.string("language", "command.locale.sub.set.arg.language").asRequired().withAutoComplete()))
                .addSubCommand("list", "command.locale.sub.list")
                .adminCommand());
        this.guilds = guilds;
        this.executorService = executorService;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var subCmd = event.getSubcommandName();
        if ("set".equalsIgnoreCase(subCmd)) {
            set(event, context);
        }
        if ("list".equalsIgnoreCase(subCmd)) {
            list(event, context);
        }
    }

    private void set(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var language = context.localizer().localizer().getLanguage(event.getOption("language").getAsString());
        if (language.isEmpty()) {
            event.reply(context.localize("command.locale.error.invalidLocale")).setEphemeral(true).queue();
            return;
        }
        if (guilds.guild(event.getGuild()).settings().general().language(language.get())) {
            event.reply(context.localize("command.locale.sub.set.set",
                    Replacement.create("LOCALE", language.get().getLanguage(), Format.CODE))).queue();
            context.commandHub().refreshGuildCommands(event.getGuild());
            executorService.schedule(() -> context.commandHub().refreshGuildCommands(event.getGuild()), 5, TimeUnit.SECONDS);
        }
    }

    private void list(SlashCommandInteractionEvent event, SlashCommandContext context) {
        var languages = context.localizer().localizer().languages();
        var builder = TextFormatting.getTableBuilder(languages,
                context.localize("words.language"), context.localize("words.code"));
        languages.forEach(lang -> builder.setNextRow(lang.getLanguage(), lang.getCode()));
        event.reply(context.localize("command.locale.sub.list.list") + "\n" + builder).queue();
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event, SlashCommandContext context) {
        var option = event.getFocusedOption();
        if ("language".equalsIgnoreCase(option.getName())) {
            event.replyChoices(Completion.complete(option.getValue(), context.localizer().localizer().languages(), Language::getLanguage)).queue();
        }
    }
}
