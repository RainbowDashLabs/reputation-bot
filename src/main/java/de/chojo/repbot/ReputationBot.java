package de.chojo.repbot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.chojo.jdautil.listener.CommandHub;
import de.chojo.jdautil.localization.Localizer;
import de.chojo.jdautil.localization.util.Language;
import de.chojo.repbot.commands.Channel;
import de.chojo.repbot.commands.Help;
import de.chojo.repbot.commands.Invite;
import de.chojo.repbot.commands.Locale;
import de.chojo.repbot.commands.Log;
import de.chojo.repbot.commands.Prefix;
import de.chojo.repbot.commands.RepSettings;
import de.chojo.repbot.commands.Reputation;
import de.chojo.repbot.commands.Roles;
import de.chojo.repbot.commands.Scan;
import de.chojo.repbot.commands.Source;
import de.chojo.repbot.commands.Thankwords;
import de.chojo.repbot.commands.TopReputation;
import de.chojo.repbot.config.Configuration;
import de.chojo.repbot.data.GuildData;
import de.chojo.repbot.data.updater.QueryReplacement;
import de.chojo.repbot.data.updater.SqlUpdater;
import de.chojo.repbot.listener.MessageListener;
import de.chojo.repbot.listener.ReactionListener;
import de.chojo.repbot.listener.StateListener;
import de.chojo.repbot.manager.MemberCacheManager;
import de.chojo.repbot.manager.ReputationManager;
import de.chojo.repbot.manager.RoleAssigner;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class ReputationBot {
    private static final Logger log = getLogger(ReputationBot.class);
    private static ReputationBot instance;
    private final ExecutorService executorService = Executors.newFixedThreadPool(50);
    private final ScheduledExecutorService cleaner = Executors.newScheduledThreadPool(1);
    private ShardManager shardManager;
    private HikariDataSource dataSource;
    private Configuration configuration;
    private Localizer localizer;
    private Scan scan;
    private MemberCacheManager memberCacheManager;

    public static void main(String[] args) throws SQLException, IOException {
        ReputationBot.instance = new ReputationBot();
        instance.start();
    }

    private void start() throws SQLException, IOException {
        configuration = Configuration.create();

        log.info("Initializing connection pool");

        initDatabase();

        log.info("Creating Shutdown Hook");
        initShutdownHook();

        initLocalization();

        log.info("Initializing JDA");
        try {
            initJDA();
        } catch (LoginException e) {
            log.error("Could not login.", e);
            return;
        }

        log.info("Initializing bot.");
        initBot();
    }

    private void initDatabase() throws SQLException, IOException {
        var connectionPool = getConnectionPool(null);

        var schema = configuration.database().schema();
        SqlUpdater.builder(connectionPool)
                .setReplacements(new QueryReplacement("repbot_schema", schema))
                .setVersionTable(schema + ".repbot_version")
                .setSchemas(schema)
                .execute();

        dataSource = getConnectionPool(configuration.database().schema());
    }

    private void initLocalization() {
        localizer = Localizer.builder(Language.ENGLISH)
                .addLanguage(Language.GERMAN)
                .withLanguageProvider(guild -> new GuildData(dataSource).getLanguage(guild))
                .withBundlePath("locale")
                .build();
    }

    private void initBot() {
        var roleAssigner = new RoleAssigner(dataSource);
        var reputationManager = new ReputationManager(dataSource, roleAssigner, configuration.magicImage());
        var reactionListener = new ReactionListener(dataSource, localizer, reputationManager);
        var stateListener = new StateListener(dataSource);
        cleaner.scheduleAtFixedRate(stateListener, 0, 12, TimeUnit.HOURS);

        // TODO: 28.05.2021 NE MENGE!
        shardManager.addEventListener(
                new MessageListener(dataSource, configuration, memberCacheManager, reactionListener, localizer, reputationManager),
                stateListener,
                reactionListener);
        var data = new GuildData(dataSource);
        var hubBuilder = CommandHub.builder(shardManager, configuration.defaultPrefix())
                .receiveGuildMessage()
                .receiveGuildMessagesUpdates()
                .withConversationSystem()
                .withPrefixResolver(data::getPrefix)
                .onlyGuildCommands()
                .withSlashCommands()
                .withCommands(
                        new Channel(dataSource, localizer),
                        new Prefix(dataSource, configuration, localizer),
                        new Reputation(dataSource, localizer),
                        new Roles(dataSource, localizer),
                        new RepSettings(dataSource, localizer),
                        new TopReputation(dataSource, localizer),
                        Thankwords.of(dataSource, localizer),
                        scan,
                        new Locale(dataSource, localizer),
                        new Invite(localizer),
                        new Source(localizer),
                        new Log(shardManager, dataSource, localizer)
                )
                .withInvalidArgumentProvider(((loc, command) -> {
                    var embedBuilder = new EmbedBuilder()
                            .setTitle(loc.localize("error.invalidArguments"))
                            .appendDescription(command.args() != null ? command.command() + " " + command.args() + "\n" : "");
                    if (command.getSubCommands().length != 0) {
                        embedBuilder.appendDescription(">>> " + Arrays.stream(command.getSubCommands())
                                .map(c -> command.command() + " " + c.name() + (c.args() == null ? "" : " " + c.args()))
                                .collect(Collectors.joining("\n")));
                    }
                    return embedBuilder.build();
                }))
                .withLocalizer(localizer)
                .withPermissionCheck((wrapper, command) -> {
                    if (wrapper.getMember().hasPermission(command.permission())) return true;
                    var guildSettings = data.getGuildSettings(wrapper.getGuild());
                    if (guildSettings.isEmpty()) return false;
                    var settings = guildSettings.get();
                    var roleById = wrapper.getGuild().getRoleById(settings.managerRole().orElse(0));
                    if (roleById == null) return false;
                    return wrapper.getMember().getRoles().contains(roleById);
                });
        if (configuration.testMode().isTestMode()) {
            hubBuilder.onlyGuildCommands(configuration.testMode().testGuilds());
        }
        var hub = hubBuilder.build();
        hub.registerCommands(new Help(hub, localizer, configuration.isExclusiveHelp()));
    }

    private void initShutdownHook() {
        var shutdown = new Thread(() -> {
            log.info("Shuting down shardmanager.");
            shardManager.shutdown();
            log.info("Shutting down scheduler.");
            cleaner.shutdown();
            log.info("Shutting down database connections.");
            dataSource.close();
            log.info("Bot shutdown complete.");
        });
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    private void initJDA() throws LoginException {
        scan = new Scan(dataSource, localizer);
        memberCacheManager = new MemberCacheManager(scan);
        shardManager = DefaultShardManagerBuilder.createDefault(configuration.getToken())
                .enableIntents(
                        GatewayIntent.GUILD_MESSAGE_REACTIONS,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.GUILD_EMOJIS)
                .enableCache(CacheFlag.EMOTE)
                .setEnableShutdownHook(false)
                .setMemberCachePolicy(memberCacheManager)
                .setEventPool(executorService)
                .build();
    }

    private HikariDataSource getConnectionPool(@Nullable String schema) {
        var db = configuration.database();
        var props = new Properties();
        props.setProperty("dataSourceClassName", PGSimpleDataSource.class.getName());
        props.setProperty("dataSource.serverName", db.host());
        props.setProperty("dataSource.portNumber", db.port());
        props.setProperty("dataSource.user", db.user());
        props.setProperty("dataSource.password", db.password());
        props.setProperty("dataSource.databaseName", db.database());

        var config = new HikariConfig(props);
        config.setMaximumPoolSize(db.poolSize());
        if (schema != null) {
            config.setSchema(db.schema());
        }

        return new HikariDataSource(config);
    }
}
