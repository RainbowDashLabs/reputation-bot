package de.chojo.repbot.commands;

import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.localization.Localizer;
import de.chojo.jdautil.localization.util.LocalizedEmbedBuilder;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.parsing.DiscordResolver;
import de.chojo.jdautil.parsing.Verifier;
import de.chojo.jdautil.wrapper.CommandContext;
import de.chojo.jdautil.wrapper.MessageEventWrapper;
import de.chojo.repbot.analyzer.MessageAnalyzer;
import de.chojo.repbot.data.GuildData;
import de.chojo.repbot.data.ReputationData;
import de.chojo.repbot.util.TextGenerator;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.slf4j.LoggerFactory.getLogger;

public class Scan extends SimpleCommand {
    public static final int INTERVAL_MS = 2000;
    private static final int SCAN_THREADS = 10;
    private static final Logger log = getLogger(Scan.class);
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(SCAN_THREADS + 1,
            runnable -> {
                var t = new Thread(runnable, "Scanner");
                t.setUncaughtExceptionHandler((thread, err) -> log.error("Unhandled exception in Scanner Thread {}.", thread.getId(), err));
                return t;
            });
    private final GuildData guildData;
    private final ReputationData reputationData;
    private final Localizer loc;
    private final Set<Long> activeScans = new HashSet<>();
    private final Set<Long> cancel = new HashSet<>();
    private final Queue<ScanProcess> finished = new ArrayDeque<>();
    private final Queue<ScanProcess> canceled = new ArrayDeque<>();
    private final MessageAnalyzer messageAnalyzer;

    public Scan(DataSource dataSource, MessageAnalyzer messageAnalyzer, Localizer localizer) {
        super("scan",
                null,
                "command.scan.description",
                subCommandBuilder()
                        .add("start", "command.scan.description", argsBuilder()
                                .add(OptionType.CHANNEL, "channel", "channel")
                                .add(OptionType.INTEGER, "number_messages", "number_messages")
                                .build()
                        )
                        .add("cancel", "command.scan.sub.cancel")
                        .build(),
                Permission.MANAGE_SERVER);
        guildData = new GuildData(dataSource);
        reputationData = new ReputationData(dataSource);
        loc = localizer;
        executorService.scheduleAtFixedRate(() -> {
            Thread.currentThread().setName("Scan Backsync");
            finishTasks();
            finishCanceledTasks();
        }, 1, 1, TimeUnit.SECONDS);
        this.messageAnalyzer = messageAnalyzer;
    }

    @Override
    public boolean onCommand(MessageEventWrapper eventWrapper, CommandContext context) {
        if (!eventWrapper.getGuild().getSelfMember().hasPermission(Permission.MESSAGE_HISTORY)) {
            eventWrapper.replyErrorAndDelete(eventWrapper.localize("command.scan.error.history"), 10);
            return true;
        }

        if (context.argsEmpty()) return false;
        var subCmd = context.argString(0).get();
        if ("cancel".equalsIgnoreCase(subCmd)) {
            if (!activeScans.contains(eventWrapper.getGuild().getIdLong())) {
                eventWrapper.replyErrorAndDelete(eventWrapper.localize("command.scan.sub.cancel.noTask"), 10);
                return true;
            }
            cancelScan(eventWrapper.getGuild());
            return true;
        }

        if ("start".equalsIgnoreCase(subCmd)) {
            context = context.subContext(subCmd);
            if (activeScans.contains(eventWrapper.getGuild().getIdLong())) {
                eventWrapper.replyErrorAndDelete(":stop_sign: " + eventWrapper.localize("command.scan.error.running"), 10);
                return true;
            }

            if (activeScans.size() >= SCAN_THREADS) {
                eventWrapper.replyErrorAndDelete(":stop_sign: " + eventWrapper.localize("command.scan.error.queueFull"), 10);
                return true;
            }

            if (context.argsEmpty()) {
                scanChannel(eventWrapper, eventWrapper.getTextChannel(), 30000);
                return true;
            }

            var messages = 30000;
            if (context.argInt(1).isPresent()) {
                messages = Math.max(context.argInt(1).get(), 0);
            }

            var channel = eventWrapper.getTextChannel();
            if (context.argString(0).isPresent()) {
                channel = DiscordResolver.getTextChannel(eventWrapper.getGuild(), context.argString(0).get()).orElse(null);
            }

            if (channel == null) {
                eventWrapper.replyErrorAndDelete(eventWrapper.localize("error.invalidChannel"), 10);
                return true;
            }
            scanChannel(eventWrapper, channel, messages);
        }
        return false;
    }

    @Override
    public void onSlashCommand(SlashCommandEvent event) {
        var loc = this.loc.getContextLocalizer(event.getGuild());
        if (!event.getGuild().getSelfMember().hasPermission(Permission.MESSAGE_HISTORY)) {
            event.reply(loc.localize("command.scan.error.history")).setEphemeral(true).queue();
            return;
        }

        var subCmd = event.getSubcommandName();

        if ("cancel".equalsIgnoreCase(subCmd)) {
            if (!activeScans.contains(event.getGuild().getIdLong())) {
                event.reply(loc.localize("command.scan.sub.cancel.noTask")).setEphemeral(true).queue();
                return;
            }
            event.reply(loc.localize("command.scan.canceling")).queue();
            cancelScan(event.getGuild());
            return;
        }

        if ("start".equalsIgnoreCase(subCmd)) {

            if (activeScans.contains(event.getGuild().getIdLong())) {
                event.reply(":stop_sign: " + loc.localize("command.scan.error.running")).setEphemeral(true).queue();
                return;
            }

            if (activeScans.size() >= SCAN_THREADS) {
                event.reply(":stop_sign: " + loc.localize("command.scan.error.queueFull")).setEphemeral(true).queue();
                return;
            }

            if (event.getOptions().isEmpty()) {
                scanChannel(event, event.getTextChannel(), 30000);
                return;
            }
            var messages = 30000;
            var channel = event.getTextChannel();
            if (event.getOption("number_messages") != null) {
                messages = (int) event.getOption("number_messages").getAsLong();
            }
            if (event.getOption("channel") != null) {
                event.getOption("channel").getAsGuildChannel();
            }
            scanChannel(event, channel, Math.max(messages, 0));
        }
    }

    private void scanChannel(MessageEventWrapper eventWrapper, TextChannel channel, int messageCount) {
        var duration = DurationFormatUtils.formatDuration((long) messageCount / 100 * INTERVAL_MS, "mm:ss");
        eventWrapper.reply(eventWrapper.localize("command.scan.scheduling", Replacement.create("DURATION", duration))).queue();
        preSchedule(channel, messageCount);
    }

    private void scanChannel(SlashCommandEvent event, TextChannel channel, int messageCount) {
        var duration = DurationFormatUtils.formatDuration((long) messageCount / 100 * INTERVAL_MS, "mm:ss");
        event.reply(loc.localize("command.scan.scheduling", event.getGuild(), Replacement.create("DURATION", duration))).queue();
        preSchedule(channel, messageCount);
    }

    private void preSchedule(TextChannel channel, int messageCount) {
        var history = channel.getHistory();

        var guildSettings = guildData.getGuildSettings(channel.getGuild());
        if (guildSettings.isEmpty()) return;
        var pattern = guildSettings.get().thankwordPattern();

        schedule(history, pattern, channel, messageCount);
    }

    private void schedule(MessageHistory history, Pattern pattern, TextChannel reportChannel, int calls) {
        var loc = this.loc.getContextLocalizer(reportChannel.getGuild());
        var progressMessage = reportChannel.sendMessage(loc.localize("command.scan.progress",
                Replacement.create("PERCENT", String.format("%.02f", 0d))) + " " + TextGenerator.progressBar(0, 40)).complete();
        var scanProcess = new ScanProcess(messageAnalyzer, this.loc, progressMessage, history, pattern, calls, reputationData);

        activeScans.add(reportChannel.getGuild().getIdLong());
        reportChannel.getGuild().loadMembers().get();
        executorService.schedule(() -> processScan(scanProcess), 0, TimeUnit.SECONDS);
    }

    private void processScan(ScanProcess scan) {
        if (cancel.remove(scan.guild().getIdLong())) {
            canceled.add(scan);
            return;
        }
        if (scan.scan()) {
            executorService.schedule(() -> processScan(scan), Math.max(0, INTERVAL_MS - scan.getTime()), TimeUnit.MILLISECONDS);
        } else {
            finishScan(scan);
        }
    }

    private void finishTasks() {
        if (finished.isEmpty()) return;
        var scan = finished.poll();
        activeScans.remove(scan.guild().getIdLong());
        scan.progressMessage().editMessage(loc.localize("command.scan.progress", scan.guild(),
                Replacement.create("PERCENT", String.format("%.02f", 100d))) + " " + TextGenerator.progressBar(1, 40)).queue();
        var embed = new LocalizedEmbedBuilder(loc, scan.guild())
                .setTitle("command.scan.completed")
                .setDescription(loc.localize("command.scan.result", scan.guild(),
                        Replacement.create("SCANNED", scan.scanned()),
                        Replacement.create("HITS", scan.hits())))
                .build();
        scan.resultChannel().sendMessage(embed).reference(scan.progressMessage()).queue();
    }

    private void finishCanceledTasks() {
        if (canceled.isEmpty()) return;
        var scan = canceled.poll();
        activeScans.remove(scan.guild().getIdLong());
        var embed = new LocalizedEmbedBuilder(loc, scan.guild())
                .setTitle("command.scan.canceled")
                .setDescription(loc.localize("command.scan.result", scan.guild(),
                        Replacement.create("SCANNED", scan.scanned()),
                        Replacement.create("HITS", scan.hits())))
                .build();
        scan.resultChannel().sendMessage(embed).reference(scan.progressMessage()).queue();
    }

    public boolean isRunning(Guild guild) {
        return activeScans.contains(guild.getIdLong());
    }

    public void cancelScan(Guild guild) {
        activeScans.remove(guild.getIdLong());
        cancel.add(guild.getIdLong());
    }

    public void finishScan(ScanProcess scanProcess) {
        activeScans.remove(scanProcess.guild().getIdLong());
        finished.add(scanProcess);
    }

    private static class ScanProcess {
        private final MessageAnalyzer messageAnalyzer;
        private final Localizer loc;
        private final Guild guild;
        private final TextChannel resultChannel;
        private final Message progressMessage;
        private final MessageHistory history;
        private final Pattern pattern;
        private final int calls;
        private final ReputationData reputationData;
        private int scanned;
        private int hits;
        private int callsLeft;
        private long time;

        public ScanProcess(MessageAnalyzer messageAnalyzer, Localizer localizer, Message progressMessage, MessageHistory history, Pattern pattern, int calls, ReputationData data) {
            this.messageAnalyzer = messageAnalyzer;
            loc = localizer;
            this.guild = progressMessage.getGuild();
            this.resultChannel = progressMessage.getTextChannel();
            this.progressMessage = progressMessage;
            this.history = history;
            this.pattern = pattern;
            this.calls = Math.min(Math.max(0, calls), 100000);
            this.callsLeft = this.calls;
            reputationData = data;
        }

        public void countScan() {
            scanned++;
        }

        public void hit() {
            hits++;
        }

        public boolean scan() {
            if (callsLeft == 0) return false;
            var start = Instant.now();
            var size = history.size();
            var messages = history.retrievePast(Math.min(callsLeft, 100)).timeout(10, TimeUnit.SECONDS).complete();
            callsLeft -= Math.min(callsLeft, 100);
            if (size == history.size()) {
                return false;
            }

            for (var message : messages) {
                countScan();

                if (message.getAuthor().isBot()) continue;

                var result = messageAnalyzer.processMessage(pattern, message, null, false, 3);

                var donator = result.donator();
                var refMessage = result.referenceMessage();
                for (var resultReceiver : result.receivers()) {
                    switch (result.type()) {
                        case FUZZY, MENTION, ANSWER -> {
                            if (Verifier.equalSnowflake(donator, resultReceiver.getReference())) continue;
                            if (reputationData.logReputation(guild, donator, resultReceiver.getReference().getUser(), message, refMessage, result.type())) {
                                hit();
                            }
                        }
                        case NO_MATCH -> {
                        }
                    }
                }
            }
            var progress = (calls - Math.max(callsLeft, 0)) / (double) calls;
            progressMessage.editMessage(loc.localize("command.scan.progress", guild,
                    Replacement.create("PERCENT", String.format("%.02f", progress * 100d))) + " " + TextGenerator.progressBar(progress, 40)).complete();
            time = Instant.now().until(start, ChronoUnit.MILLIS);
            return callsLeft > 0;
        }

        public long getTime() {
            return time;
        }

        public Guild guild() {
            return guild;
        }

        public Message progressMessage() {
            return progressMessage;
        }

        public int scanned() {
            return scanned;
        }

        public int hits() {
            return hits;
        }

        public TextChannel resultChannel() {
            return resultChannel;
        }
    }
}
