package de.chojo.repbot.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.chojo.repbot.config.elements.Badges;
import de.chojo.repbot.config.elements.Database;
import de.chojo.repbot.config.elements.Links;
import de.chojo.repbot.config.elements.MagicImage;
import de.chojo.repbot.config.elements.TestMode;
import de.chojo.repbot.config.exception.ConfigurationException;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.slf4j.LoggerFactory.getLogger;

public class Configuration {
    private static final Logger log = getLogger(Configuration.class);
    private final ObjectMapper objectMapper;
    private ConfigFile configFile;

    private Configuration() {
        objectMapper = new ObjectMapper()
        .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        .setDefaultPrettyPrinter(new DefaultPrettyPrinter())
                .configure(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, true);
    }

    public static Configuration create() {
        var configuration = new Configuration();
        configuration.reload();
        return configuration;
    }

    public void reload() {
        try {
            reloadFile();
        } catch (IOException e) {
            log.info("Could not load config", e);
            throw new ConfigurationException("Could not load config file", e);
        }
        try {
            save();
        } catch (IOException e) {
            log.error("Could not save config.");
        }
    }

    private void save() throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValues(getConfig().toFile()).write(configFile);
    }

    private void reloadFile() throws IOException {
        forceConsistency();
        configFile = objectMapper.readValue(getConfig().toFile(), ConfigFile.class);
    }

    private void forceConsistency() throws IOException {
        Files.createDirectories(getConfig().getParent());
        if (!getConfig().toFile().exists()) {
            if (getConfig().toFile().createNewFile()) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValues(getConfig().toFile()).write(new ConfigFile());
                throw new ConfigurationException("Please configure the config.");
            }
        }
    }

    private Path getConfig() {
        var home = new File(".").getAbsoluteFile().getParentFile().toPath();
        var property = System.getProperty("bot.config");
        if (property == null) {
            log.error("bot.config property is not set.");
            throw new ConfigurationException("Property -Dbot.config=<config path> is not set.");
        }
        return Paths.get(home.toString(), property);
    }

    // DELEGATES
    public String token() {
        return configFile.token();
    }

    public String defaultPrefix() {
        return configFile.defaultPrefix();
    }

    public Database database() {
        return configFile.database();
    }

    public boolean isExclusiveHelp() {
        return configFile.isExclusiveHelp();
    }

    public MagicImage magicImage() {
        return configFile.magicImage();
    }

    public TestMode testMode() {
        return configFile.testMode();
    }

    public Badges badges() {
        return configFile.badges();
    }

    public Links links() {
        return configFile.links();
    }
}
