package de.chojo.repbot.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

@Getter
@Slf4j
public class Configuration {
    private final ObjectMapper objectMapper;
    private ConfigFile configFile;

    private Configuration() {
        objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        objectMapper.setDefaultPrettyPrinter(new DefaultPrettyPrinter());
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
        }
    }

    private void reloadFile() throws IOException {
        forceConsistency();
        configFile = objectMapper.readValue(getConfig().toFile(), ConfigFile.class);
    }

    private void forceConsistency() throws IOException {
        if (!getConfig().toFile().exists()) {
            if (getConfig().toFile().createNewFile()) {
                objectMapper.writeValue(getConfig().toFile(), new ConfigFile());
            }
        }
    }

    private Path getConfig() {
        var home = new File(".").getAbsoluteFile().getParentFile().toPath();
        var property = System.getProperty("bot.config");

        return Paths.get(home.toString(), property);
    }

    public <T> T get(Function<ConfigFile, T> get) {
        return get.apply(configFile);
    }

    public ConfigFile get() {
        return configFile;
    }
}