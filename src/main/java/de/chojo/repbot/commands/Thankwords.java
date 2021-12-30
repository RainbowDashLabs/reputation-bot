package de.chojo.repbot.commands;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.localization.Localizer;
import de.chojo.jdautil.localization.util.Format;
import de.chojo.jdautil.localization.util.LocalizedEmbedBuilder;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.parsing.Verifier;
import de.chojo.jdautil.wrapper.SlashCommandContext;
import de.chojo.repbot.analyzer.MessageAnalyzer;
import de.chojo.repbot.data.GuildData;
import de.chojo.repbot.serialization.ThankwordsContainer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class Thankwords extends SimpleCommand {

    private static final Logger log = getLogger(Thankwords.class);
    private final GuildData data;
    private final Localizer loc;
    private final ThankwordsContainer thankwordsContainer;
    private final MessageAnalyzer messageAnalyzer;

    private Thankwords(MessageAnalyzer messageAnalyzer, GuildData data, Localizer localizer, ThankwordsContainer thankwordsContainer) {
        super("thankwords", new String[]{"tw"},
                "command.thankwords.description",
                subCommandBuilder()
                        .add("add", "command.thankwords.sub.add", argsBuilder()
                                .add(OptionType.STRING, "pattern", "pattern", true)
                                .build())
                        .add("remove", "pattern", argsBuilder()
                                .add(OptionType.STRING, "pattern", "pattern", true)
                                .build())
                        .add("list", "command.thankwords.sub.list")
                        .add("check", "command.thankwords.sub.check", argsBuilder()
                                .add(OptionType.STRING, "message", "message", true)
                                .build()
                        )
                        .add("loaddefault", "command.thankwords.sub.loadDefault", argsBuilder()
                                .add(OptionType.STRING, "language", "language")
                                .build()
                        ).build(),
                Permission.MANAGE_SERVER);
        this.data = data;
        loc = localizer;
        this.thankwordsContainer = thankwordsContainer;
        this.messageAnalyzer = messageAnalyzer;
    }

    public static Thankwords of(MessageAnalyzer messageAnalyzer, DataSource dataSource, Localizer localizer) {
        ThankwordsContainer thankwordsContainer;
        try {
            thankwordsContainer = loadContainer();
        } catch (IOException e) {
            thankwordsContainer = null;
            log.error("Could not read thankwords", e);
        }
        return new Thankwords(messageAnalyzer, new GuildData(dataSource), localizer, thankwordsContainer);
    }

    public static ThankwordsContainer loadContainer() throws IOException {
        try (var input = Thankwords.class.getClassLoader().getResourceAsStream("Thankswords.json")) {
            return new ObjectMapper()
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                    .readValue(input, ThankwordsContainer.class);
        }
    }

    @Override
    public void onSlashCommand(SlashCommandEvent event, SlashCommandContext context) {
        var subCmd = event.getSubcommandName();
        if ("add".equalsIgnoreCase(subCmd)) {
            add(event);
        }
        if ("remove".equalsIgnoreCase(subCmd)) {
            remove(event);
        }
        if ("list".equalsIgnoreCase(subCmd)) {
            list(event);
        }
        if ("check".equalsIgnoreCase(subCmd)) {
            check(event);
        }
        if ("loaddefault".equalsIgnoreCase(subCmd)) {
            loadDefaults(event);
        }
    }

    private boolean add(SlashCommandEvent event) {
        var loc = this.loc.getContextLocalizer(event.getGuild());
        var pattern = event.getOption("pattern").getAsString();
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            event.reply(loc.localize("error.invalidRegex"))
                    .setEphemeral(true)
                    .queue();
            return true;
        }
        if (data.addThankWord(event.getGuild(), pattern)) {
            event.reply(loc.localize("command.thankwords.sub.add.added",
                    Replacement.create("REGEX", pattern, Format.CODE))).queue();
        }
        return true;
    }

    private boolean remove(SlashCommandEvent event) {
        var loc = this.loc.getContextLocalizer(event.getGuild());
        var pattern = event.getOption("pattern").getAsString();
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            event.reply(loc.localize("error.invalidRegex"))
                    .setEphemeral(true)
                    .queue();
            return true;
        }
        if (data.removeThankWord(event.getGuild(), pattern)) {
            event.reply(loc.localize("command.thankwords.sub.remove.removed",
                    Replacement.create("PATTERN", pattern, Format.CODE))).queue();
            return true;
        }
        event.reply(loc.localize("command.thankwords.error.patternNotFound"))
                .setEphemeral(true)
                .queue();
        return true;
    }

    private boolean list(SlashCommandEvent event) {
        var loc = this.loc.getContextLocalizer(event.getGuild());

        var pattern = getGuildPattern(event.getGuild());
        if (pattern == null) return false;

        event.reply(loc.localize("command.thankwords.sub.list.list") + "\n" + pattern).queue();
        return true;
    }

    @Nullable
    private String getGuildPattern(Guild guild) {

        var guildSettings = data.getGuildSettings(guild);

        var pattern = Arrays.stream(guildSettings.thankSettings().thankwords())
                .map(w -> StringUtils.wrap(w, "`"))
                .collect(Collectors.joining(", "));
        return pattern;
    }

    private boolean check(SlashCommandEvent event) {
        var loc = this.loc.getContextLocalizer(event.getGuild());

        var guildSettings = data.getGuildSettings(event.getGuild());
        var messageId = event.getOption("message").getAsString();

        if (!Verifier.isValidId(messageId)) {
            event.reply(loc.localize("error.invalidMessage")).queue();
            return true;
        }

        var message = event.getChannel().retrieveMessageById(messageId).complete();
        var result = messageAnalyzer.processMessage(guildSettings.thankSettings().thankwordPattern(), message, guildSettings, true, 3);
        var builder = new LocalizedEmbedBuilder(this.loc, event.getGuild());
        if (result.receivers().isEmpty()) {
            event.reply(loc.localize("command.thankwords.sub.check.match.noMatch")).queue();
            return true;
        }

        var processResult = processMessage(event.getGuild(), result, builder);
        if (processResult != null) {
            event.replyEmbeds(processResult).queue();
            return true;
        }

        event.replyEmbeds(builder.build()).queue();
        return true;
    }

    private MessageEmbed processMessage(Guild guild, de.chojo.repbot.analyzer.AnalyzerResult result, LocalizedEmbedBuilder builder) {
        for (var receiver : result.receivers()) {
            switch (result.type()) {
                case FUZZY -> builder.addField("command.thankwords.sub.check.match.fuzzy",
                        loc.localize("command.thankwords.sub.check.result",
                                Replacement.create("DONATOR", result.donator().getAsMention()),
                                Replacement.create("RECEIVER", receiver.getReference().getAsMention())) + "\n"
                        + loc.localize("command.thankwords.sub.check.confidence",
                                Replacement.create("SCORE", String.format("%.3f", receiver.getWeight()))),
                        false);
                case MENTION -> builder.addField("command.thankwords.sub.check.match.mention",
                        loc.localize("command.thankwords.sub.check.result",
                                Replacement.create("DONATOR", result.donator().getAsMention()),
                                Replacement.create("RECEIVER", receiver.getReference().getAsMention())),
                        false);
                case ANSWER -> {
                    builder.addField("command.thankwords.sub.check.match.answer",
                            loc.localize("command.thankwords.sub.check.result",
                                    Replacement.create("DONATOR", result.donator().getAsMention()),
                                    Replacement.create("RECEIVER", receiver.getReference().getAsMention())) + "\n"
                            + loc.localize("command.thankwords.sub.check.reference",
                                    Replacement.create("URL", result.referenceMessage().getJumpUrl())),
                            false);

                    var match = new LocalizedEmbedBuilder(loc, guild)
                            .setTitle("command.thankwords.sub.check.match.answer")
                            .setDescription(
                                    loc.localize("command.thankwords.sub.check.result",
                                            Replacement.create("DONATOR", result.donator().getAsMention()),
                                            Replacement.create("RECEIVER", receiver.getReference().getAsMention())) + "\n"
                                    + loc.localize("command.thankwords.sub.check.reference",
                                            Replacement.create("URL", result.referenceMessage().getJumpUrl())));
                    return match.build();
                }
            }
        }

        return null;
    }

    private boolean loadDefaults(SlashCommandEvent slashCommandEvent) {
        var loc = this.loc.getContextLocalizer(slashCommandEvent.getGuild());

        var languageOption = slashCommandEvent.getOption("language");
        if (languageOption == null) {
            slashCommandEvent.reply(loc.localize("command.thankwords.sub.loadDefault.available")
                                    + " " + String.join(", ", thankwordsContainer.getAvailableLanguages())).queue();
            return true;
        }
        var language = languageOption.getAsString();
        var words = thankwordsContainer.get(language.toLowerCase(Locale.ROOT));
        if (words == null) {
            slashCommandEvent.reply(loc.localize("command.locale.error.invalidLocale"))
                    .setEphemeral(true)
                    .queue();
            return true;
        }
        for (var word : words) {
            data.addThankWord(slashCommandEvent.getGuild(), word);
        }

        var wordsJoined = words.stream().map(w -> StringUtils.wrap(w, "`")).collect(Collectors.joining(", "));

        slashCommandEvent.reply(loc.localize("command.thankwords.sub.loadDefault.added") + wordsJoined).queue();
        return true;
    }
}
