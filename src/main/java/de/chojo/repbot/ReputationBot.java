package de.chojo.repbot;

import com.zaxxer.hikari.HikariDataSource;
import de.chojo.jdautil.botlist.BotlistService;
import de.chojo.jdautil.command.dispatching.CommandHub;
import de.chojo.jdautil.interactions.dispatching.InteractionHub;
import de.chojo.jdautil.localization.ILocalizer;
import de.chojo.jdautil.localization.Localizer;
import de.chojo.jdautil.localization.util.Language;
import de.chojo.repbot.analyzer.ContextResolver;
import de.chojo.repbot.analyzer.MessageAnalyzer;
import de.chojo.repbot.commands.abuseprotection.AbuseProtection;
import de.chojo.repbot.commands.Channel;
import de.chojo.repbot.commands.Dashboard;
import de.chojo.repbot.commands.Debug;
import de.chojo.repbot.commands.Gdpr;
import de.chojo.repbot.commands.Info;
import de.chojo.repbot.commands.Invite;
import de.chojo.repbot.commands.Locale;
import de.chojo.repbot.commands.Log;
import de.chojo.repbot.commands.Messages;
import de.chojo.repbot.commands.Prune;
import de.chojo.repbot.commands.Reactions;
import de.chojo.repbot.commands.RepAdmin;
import de.chojo.repbot.commands.RepSettings;
import de.chojo.repbot.commands.Reputation;
import de.chojo.repbot.commands.Roles;
import de.chojo.repbot.commands.Scan;
import de.chojo.repbot.commands.Setup;
import de.chojo.repbot.commands.Thankwords;
import de.chojo.repbot.commands.Top;
import de.chojo.repbot.config.Configuration;
import de.chojo.repbot.dao.access.Cleanup;
import de.chojo.repbot.dao.provider.Guilds;
import de.chojo.repbot.dao.provider.Metrics;
import de.chojo.repbot.listener.InternalCommandListener;
import de.chojo.repbot.listener.LogListener;
import de.chojo.repbot.listener.MessageListener;
import de.chojo.repbot.listener.ReactionListener;
import de.chojo.repbot.listener.StateListener;
import de.chojo.repbot.listener.VoiceStateListener;
import de.chojo.repbot.listener.voting.ReputationVoteListener;
import de.chojo.repbot.service.GdprService;
import de.chojo.repbot.service.PresenceService;
import de.chojo.repbot.service.RepBotCachePolicy;
import de.chojo.repbot.service.ReputationService;
import de.chojo.repbot.service.RoleAssigner;
import de.chojo.repbot.service.RoleUpdater;
import de.chojo.repbot.service.SelfCleanupService;
import de.chojo.repbot.statistic.Statistic;
import de.chojo.repbot.util.LogNotify;
import de.chojo.repbot.util.PermissionErrorHandler;
import de.chojo.repbot.web.Api;
import de.chojo.sqlutil.databases.SqlType;
import de.chojo.sqlutil.datasource.DataSourceCreator;
import de.chojo.sqlutil.updater.QueryReplacement;
import de.chojo.sqlutil.updater.SqlUpdater;
import de.chojo.sqlutil.wrapper.QueryBuilderConfig;
import io.javalin.Javalin;
import io.javalin.plugin.openapi.OpenApiOptions;
import io.javalin.plugin.openapi.OpenApiPlugin;
import io.javalin.plugin.openapi.ui.ReDocOptions;
import io.javalin.plugin.openapi.ui.SwaggerOptions;
import io.javalin.plugin.openapi.utils.OpenApiVersionUtil;
import io.swagger.v3.oas.models.info.License;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import static org.slf4j.LoggerFactory.getLogger;

public class ReputationBot {
    private static final Logger log = getLogger(ReputationBot.class);
    private static final Thread.UncaughtExceptionHandler EXCEPTION_HANDLER =
            (t, e) -> log.error(LogNotify.NOTIFY_ADMIN, "An uncaught exception occured in " + t.getName() + "-" + t.getId() + ".", e);
    private static ReputationBot instance;
    private final ThreadGroup eventGroup = new ThreadGroup("Event Worker");
    private final ThreadGroup workerGroup = new ThreadGroup("Scheduled Worker");
    private final ThreadGroup hikariGroup = new ThreadGroup("Hikari Worker");
    private final ThreadGroup jdaGroup = new ThreadGroup("JDA Worker");
    private final ExecutorService eventThreads = Executors.newFixedThreadPool(20, createThreadFactory(eventGroup));
    private final ScheduledExecutorService repBotWorker = Executors.newScheduledThreadPool(3, createThreadFactory(workerGroup));
    private ShardManager shardManager;
    private HikariDataSource dataSource;
    private Configuration configuration;
    private ILocalizer localizer;
    private Scan scan;
    private RepBotCachePolicy repBotCachePolicy;
    private Roles roles;
    private RoleAssigner roleAssigner;
    private Guilds guilds;
    private de.chojo.repbot.dao.access.Gdpr gdpr;
    private Cleanup cleanup;
    private Metrics metrics;
    private Javalin javalin;

    public static void main(String[] args) throws SQLException, IOException {
        ReputationBot.instance = new ReputationBot();
        instance.start();
    }

    private static ThreadFactory createThreadFactory(ThreadGroup group) {
        return r -> {
            var thread = new Thread(group, r, group.getName());
            thread.setUncaughtExceptionHandler(EXCEPTION_HANDLER);
            return thread;
        };
    }

    /**
     * Starts the bot.
     *
     * @throws SQLException If the database connection fails.
     * @throws IOException  If the configuration file fails to load.
     */
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


        initApi();
    }

    private void initApi() {
        var api = configuration.api();

        var info = new io.swagger.v3.oas.models.info.Info().version("1.0").title("Reputation Bot API")
                .description("Documentation for the Reputation Bot API")
                .license(new License().name("GNU Affero General Public License v3.0")
                        .url("https://github.com/RainbowDashLabs/reputation-bot/blob/master/LICENSE.md"));
        var options = new OpenApiOptions(info)
                .path("/json-docs")
                .reDoc(new ReDocOptions("/redoc")) // endpoint for redoc
                .swagger(new SwaggerOptions("/docs").title("Reputation Bot API"));
        OpenApiVersionUtil.INSTANCE.setLogWarnings(false);

        javalin = Javalin.create(config -> config.registerPlugin(new OpenApiPlugin(options)))
                .start(api.host(), api.port());
        new Api(javalin, metrics).init();
        initBotList();
    }

    private void initBotList() {
        var botlist = configuration.botlist();
        if (!botlist.isSubmit()) return;
        BotlistService.build(shardManager)
                .forDiscordBotListCOM(botlist.discordBotlistCom())
                .forDiscordBotsGG(botlist.discordBotsGg())
                .forTopGG(botlist.topGg())
                .forBotlistMe(botlist.botListMe())
                .withExecutorService(repBotWorker)
                .withVoteService(builder -> builder
                        .withVoteWeebhooks(javalin)
                        .onVote(voteData -> shardManager
                                .retrieveUserById(voteData.userId())
                                .flatMap(User::openPrivateChannel)
                                .flatMap(channel -> channel.sendMessage("Thanks for voting <3"))
                                .queue(message -> log.debug("Vote received"),
                                        err -> ErrorResponseException.ignore(ErrorResponse.UNKNOWN_USER,
                                                ErrorResponse.CANNOT_SEND_TO_USER))
                        )
                        .build()
                )
                .build();
    }

    private void initDatabase() throws SQLException, IOException {
        dataSource = getConnectionPool(true);

        var updatePool = getConnectionPool(false);
        var schema = configuration.database().schema();
        SqlUpdater.builder(updatePool, SqlType.POSTGRES)
                .setReplacements(new QueryReplacement("repbot_schema", schema))
                .setVersionTable(schema + ".repbot_version")
                .setSchemas(schema)
                .execute();

        var logger = getLogger("DbLogger");
        QueryBuilderConfig.setDefault(QueryBuilderConfig.builder()
                .withExceptionHandler(err -> {
                    logger.error(LogNotify.NOTIFY_ADMIN, "An error occured during a database request", err);
                })
                .withExecutor(repBotWorker)
                .build());

        guilds = new Guilds(dataSource);
        gdpr = new de.chojo.repbot.dao.access.Gdpr(dataSource);
        cleanup = new Cleanup(dataSource);
        updatePool.close();

    }

    private void initLocalization() {
        localizer = Localizer.builder(DiscordLocale.ENGLISH_US)
                .addLanguage(DiscordLocale.GERMAN,
                        DiscordLocale.SPANISH,
                        DiscordLocale.FRENCH,
                        DiscordLocale.PORTUGUESE_BRAZILIAN,
                        DiscordLocale.RUSSIAN)
                .withLanguageProvider(guild -> guilds.guild(guild).settings().general().language())
                .withBundlePath("locale")
                .build();
    }

    private void initBot() {
        metrics = new Metrics(dataSource);
        RestAction.setDefaultFailure(throwable -> {
            if (throwable instanceof InsufficientPermissionException) {
                PermissionErrorHandler.handle((InsufficientPermissionException) throwable, shardManager, localizer, configuration);
                return;
            }
            if (throwable instanceof ErrorResponseException e) {
                if (e.getErrorResponse() == ErrorResponse.UNKNOWN_INTERACTION) {
                    metrics.service().failedInteraction();
                    log.debug("Interaction timed out", e);
                    return;
                }
            }
            log.error(LogNotify.NOTIFY_ADMIN, "Unhandled exception occured: ", throwable);
        });

        RestAction.setDefaultSuccess(suc -> {
            if (suc instanceof InteractionHook) {
                metrics.service().successfulInteraction();
            }
        });
        var statistic = Statistic.of(shardManager, metrics, repBotWorker);

        var contextResolver = new ContextResolver(dataSource, configuration);
        var messageAnalyzer = new MessageAnalyzer(contextResolver, configuration, metrics);

        PresenceService.start(shardManager, configuration, statistic, repBotWorker);
        scan.lateInit(messageAnalyzer);

        // init services
        var reputationService = new ReputationService(guilds, contextResolver, roleAssigner, configuration.magicImage(), localizer);
        var gdprService = GdprService.of(shardManager, guilds, gdpr, repBotWorker);
        SelfCleanupService.create(shardManager, localizer, guilds, cleanup, configuration, repBotWorker);

        if (configuration.baseSettings().isInternalCommands()) {
            shardManager.addEventListener(new InternalCommandListener(configuration, statistic, metrics));
        }

        InteractionHub.builder(shardManager)
                .withConversationSystem()
                .withCommands(
                        new Channel(guilds),
                        new Reputation(guilds, configuration, roleAssigner),
                        roles,
                        new RepSettings(guilds),
                        new Top(guilds),
                        Thankwords.of(messageAnalyzer, guilds),
                        scan,
                        new Locale(guilds, repBotWorker),
                        new Invite(configuration),
                        Info.create(configuration),
                        new Log(guilds),
                        Setup.of(guilds),
                        new Gdpr(gdpr),
                        new Prune(gdprService),
                        new Reactions(guilds),
                        new Dashboard(guilds),
                        new AbuseProtection(guilds),
                        new Debug(guilds),
                        new RepAdmin(guilds, configuration),
                        new Messages(guilds))
                .withLocalizer(localizer)
                .withCommandErrorHandler((context, throwable) -> {
                    if (throwable instanceof InsufficientPermissionException) {
                        PermissionErrorHandler.handle((InsufficientPermissionException) throwable, shardManager, localizer, configuration);
                        return;
                    }
                    log.error(LogNotify.NOTIFY_ADMIN, "Command execution of {} failed\n{}", context.interaction().meta().name(), context.args(), throwable);
                })
                .withDefaultMenuService()
                .withPostCommandHook(result -> metrics.commands().logCommand(result.context().interaction().meta().name()))
                .withPagination(builder -> builder.withLocalizer(localizer).previousText("pages.previous").nextText("pages.next"))
                .build();

        // init listener and services
        var reactionListener = new ReactionListener(guilds, localizer, reputationService, configuration);
        var voteListener = new ReputationVoteListener(guilds, reputationService, localizer, configuration);
        var messageListener = new MessageListener(localizer, configuration, guilds, repBotCachePolicy, voteListener,
                reputationService, contextResolver, messageAnalyzer);
        var voiceStateListener = VoiceStateListener.of(dataSource, repBotWorker);
        var logListener = LogListener.create(repBotWorker);
        var stateListener = StateListener.of(localizer, guilds, configuration, metrics);
        var roleUpdater = RoleUpdater.create(guilds, roleAssigner, repBotWorker);

        shardManager.addEventListener(
                reactionListener,
                voteListener,
                messageListener,
                voiceStateListener,
                logListener,
                stateListener,
                roleUpdater);
    }

    private void initShutdownHook() {
        var shutdown = new Thread(() -> {
            log.info("Shuting down shardmanager.");
            shardManager.shutdown();
            log.info("Shutting down scheduler.");
            repBotWorker.shutdown();
            log.info("Shutting down database connections.");
            dataSource.close();
            log.info("Bot shutdown complete.");
            LogManager.shutdown();
        });
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    private void initJDA() throws LoginException {
        roleAssigner = new RoleAssigner(guilds);
        scan = new Scan(guilds, configuration);
        roles = new Roles(guilds, roleAssigner);
        repBotCachePolicy = new RepBotCachePolicy(scan, roles);
        shardManager = DefaultShardManagerBuilder.createDefault(configuration.baseSettings().token())
                .enableIntents(
                        // Required to retrieve reputation emotes
                        GatewayIntent.GUILD_MESSAGE_REACTIONS,
                        // Required to scan for thankwords
                        GatewayIntent.GUILD_MESSAGES,
                        // Required to resolve member without a direct mention
                        GatewayIntent.GUILD_MEMBERS,
                        // Required to cache voice states for member relationships
                        GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(
                        // Required for voice activity
                        CacheFlag.VOICE_STATE)
                // we have our own shutdown hook
                .setEnableShutdownHook(false)
                .setMemberCachePolicy(repBotCachePolicy)
                .setEventPool(eventThreads)
                .setThreadFactory(createThreadFactory(jdaGroup))
                .build();
    }

    private HikariDataSource getConnectionPool(boolean withSchema) {
        var db = configuration.database();
        var configurationStage = DataSourceCreator.create(SqlType.POSTGRES)
                .configure(config -> config
                        .host(db.host())
                        .port(db.port())
                        .user(db.user())
                        .password(db.password())
                        .database(db.database()))
                .create()
                .withMaximumPoolSize(db.poolSize())
                .withThreadFactory(createThreadFactory(hikariGroup));
        if (withSchema) {
            configurationStage.forSchema(db.schema());
        }

        return configurationStage.build();
    }
}
