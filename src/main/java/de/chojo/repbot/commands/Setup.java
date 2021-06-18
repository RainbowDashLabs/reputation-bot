package de.chojo.repbot.commands;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.chojo.jdautil.command.SimpleArgument;
import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.conversation.Conversation;
import de.chojo.jdautil.conversation.builder.ConversationBuilder;
import de.chojo.jdautil.conversation.elements.ButtonDialog;
import de.chojo.jdautil.conversation.elements.ComponenAction;
import de.chojo.jdautil.conversation.elements.ConversationContext;
import de.chojo.jdautil.conversation.elements.Result;
import de.chojo.jdautil.conversation.elements.Step;
import de.chojo.jdautil.localization.Localizer;
import de.chojo.jdautil.localization.util.Format;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.parsing.ArgumentUtil;
import de.chojo.jdautil.parsing.DiscordResolver;
import de.chojo.jdautil.parsing.ValueParser;
import de.chojo.jdautil.wrapper.CommandContext;
import de.chojo.jdautil.wrapper.MessageEventWrapper;
import de.chojo.jdautil.wrapper.SlashCommandContext;
import de.chojo.repbot.data.GuildData;
import de.chojo.repbot.serialization.ThankwordsContainer;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.interactions.components.Button;
import net.dv8tion.jda.api.interactions.components.ButtonStyle;

public class Setup extends SimpleCommand {

  private static final Logger log = getLogger(Setup.class);
  private final Localizer localizer;
  private final GuildData guildData;
  private final ThankwordsContainer thankwordsContainer;

  public static Setup of(DataSource dataSource, Localizer localizer) {
    ThankwordsContainer thankwordsContainer;
    try {
      thankwordsContainer = new ObjectMapper()
          .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
          .readValue(Thankwords.class.getClassLoader().getResourceAsStream("Thankswords.json"),
              ThankwordsContainer.class);
    } catch (IOException e) {
      thankwordsContainer = null;
      log.error("Could not read thankwords", e);
    }
    return new Setup(localizer, dataSource, thankwordsContainer);
  }


  public Setup(Localizer localizer, DataSource dataSource,
      ThankwordsContainer thankwordsContainer) {
    super("setup", null, "command.setup.description", (SimpleArgument[]) null,
        Permission.ADMINISTRATOR);
    this.localizer = localizer;
    this.guildData = new GuildData(dataSource);
    this.thankwordsContainer = thankwordsContainer;
  }

  @Override
  public boolean onCommand(MessageEventWrapper eventWrapper, CommandContext context) {
    context.startDialog(eventWrapper, getConversation());
    return true;
  }

  @Override
  public void onSlashCommand(SlashCommandEvent event, SlashCommandContext context) {
    event.reply(localizer.localize("command.setup.dialog.starting", event.getGuild())).queue();
    context.conversationService().startDialog(event.getUser(), event.getTextChannel(),
        getConversation());
  }

  private Conversation getConversation() {
    var builder = ConversationBuilder.builder(
        Step.button("**$command.setup.dialog.welcome$**\n$command.setup.dialog.continueToProceed$",
            buttons -> buttons
                .add(Button.success("continue", "word.continue"), c -> Result.proceed(1)))
            .build())
        .addStep(1, buildCommandSetupDialogSelectLanguage())
        .addStep(2, buildCommandSetupDialogManagerRole())
        .addStep(3, buildCommandSetupDialogRoles())
        .addStep(4, buildCommandSetupDialogLoadDefaults())
        .addStep(5, buildCommandSetupDialogChannels());

    return builder.build();
  }

  private Step buildCommandSetupDialogSelectLanguage() {
    return Step.button("command.setup.dialog.selectLanguage", this::buildSelectLanguageButtons)
        .build();
  }

  private Step buildCommandSetupDialogManagerRole() {
    return Step.message("command.setup.dialog.managerRole",
        context -> {
          var role = DiscordResolver.getRole(context.getGuild(), context.getContentRaw());
          return role.map(role1 -> manage(context, role1)).orElse(Result.fail());
        })
        .button(buttons -> buttons
            .add(new ComponenAction(Button.danger("skip", "word.skip"), c -> Result.proceed(3))))
        .build();
  }

  private Result manage(ConversationContext context, Role role) {
    guildData.setManagerRole(context.getGuild(), role);
    context.reply(localizer.localize("command.roles.sub.managerRole.set", context.getGuild(),
        Replacement.createMention(role)))
        .allowedMentions(Collections.emptyList())
        .queue();
    return Result.proceed(3);
  }

  private Step buildCommandSetupDialogChannels() {
    return Step.button("command.setup.dialog.channels", this::buildSetupDialogChannelsButton)
        .message(this::onSetupDialogChannelsButtonClicked).build();
  }

  private Step buildCommandSetupDialogLoadDefaults() {
    return Step.button("command.setup.dialog.loadDefaults",
        this::buildSetupDialogDefaultsButton).build();
  }

  private Step buildCommandSetupDialogRoles() {
    return Step
        .message("command.setup.dialog.roles".stripIndent(), this::buildSetupDialogRolesButton)
        .button(buttons -> buttons
            .add(new ComponenAction(Button.success("done", "word.done"), c -> Result.proceed(4))))
        .build();
  }

  private Result onSetupDialogChannelsButtonClicked(ConversationContext context) {
    var args = context.getContentRaw().replaceAll("\\s+", " ").split("\\s");
    var channel = DiscordResolver.getTextChannels(context.getGuild(), List.of(args));
    var addedChannel = channel.stream()
        .filter(c -> guildData.addChannel(context.getGuild(), c))
        .map(IMentionable::getAsMention)
        .collect(Collectors.joining(", "));
    context.reply(
        localizer.localize("command.channel.sub.add.added", context.getGuild(),
            Replacement.create("CHANNEL", addedChannel)))
        .allowedMentions(Collections.emptyList()).queue();
    return Result.freeze();
  }

  private void buildSetupDialogChannelsButton(ButtonDialog buttons) {
    buttons.add(new ComponenAction(Button.success("done", "word.done"), c -> {
      c.reply(localizer.localize("command.setup.dialog.setupComplete", c.getGuild())).queue();
      return Result.finish();
    }));
  }

  private void buildSetupDialogDefaultsButton(ButtonDialog buttons) {
    var languages = thankwordsContainer.getAvailableLanguages();
    for (var language : languages) {
      buttons.add(Button.of(ButtonStyle.PRIMARY, language, language),
          context -> {
            var words = thankwordsContainer.get(language.toLowerCase(Locale.ROOT));
            words.forEach(word -> guildData.addThankWord(context.getGuild(), word));
            var wordsJoined = words.stream().map(w -> StringUtils.wrap(w, "`"))
                .collect(Collectors.joining(", "));
            context.reply(
                localizer.localize("command.thankwords.sub.loadDefault.added") + wordsJoined)
                .queue();
            return Result.freeze();
          });
    }
    buttons.add(Button.success("done", "word.done"), c -> Result.proceed(5));
  }

  private Result buildSetupDialogRolesButton(ConversationContext context) {
    var args = ArgumentUtil.parseQuotedArgs(context.getContentRaw(), true);
    if (args.length != 2) {
      return responseInvalid(context, "command.setup.dialog.rolesFormat");
    }
    var role = DiscordResolver.getRole(context.getGuild(), args[0]);
    if (role.isEmpty()) {
      return responseInvalid(context, "error.invalidRole");
    }
    var optionalReputation = ValueParser.parseInt(args[1]);
    return optionalReputation
        .map(reputation -> responseRolesSubAdded(context, role.get(), reputation))
        .orElseGet(() -> responseInvalid(context, "error.invalidNumber"));
  }

  @NotNull
  private Result responseInvalid(ConversationContext context, String s) {
    context.reply(localizer.localize(s, context.getGuild())).queue();
    return Result.freeze();
  }


  @NotNull
  private Result responseRolesSubAdded(ConversationContext context, Role role,
      Integer reputation) {
    guildData.addReputationRole(context.getGuild(), role, reputation);
    context.reply(localizer.localize("command.roles.sub.add.added", context.getGuild(),
        Replacement.createMention(role),
        Replacement.create("POINTS", reputation, Format.BOLD))).queue();
    return Result.freeze();
  }

  private void buildSelectLanguageButtons(ButtonDialog buttons) {
    for (var language : localizer.getLanguages()) {
      buttons.add(Button.of(ButtonStyle.PRIMARY, language.getCode(), language.getLanguage()),
          c -> {
            guildData.setLanguage(c.getGuild(), language);
            c.reply(localizer.localize("command.locale.sub.set.set", c.getGuild(),
                Replacement.create("LOCALE", language.getLanguage()))).queue();
            return Result.proceed(2);
          });
    }
  }
}
