package de.chojo.repbot.listener;

import de.chojo.repbot.dao.provider.Voice;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceMoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VoiceStateListener extends ListenerAdapter implements Runnable {
    private final Voice voice;

    public VoiceStateListener(DataSource dataSource) {
        voice = new Voice(dataSource);
    }

    public static VoiceStateListener of(DataSource dataSource, ScheduledExecutorService repBotWorker) {
        var voiceStateListener = new VoiceStateListener(dataSource);
        repBotWorker.scheduleAtFixedRate(voiceStateListener, 2, 12, TimeUnit.HOURS);
        return voiceStateListener;
    }

    @Override
    public void onGuildVoiceLeave(@NotNull GuildVoiceLeaveEvent event) {
        voice.logUser(event.getMember(), event.getChannelLeft().getMembers());
    }

    @Override
    public void onGuildVoiceMove(@NotNull GuildVoiceMoveEvent event) {
        voice.logUser(event.getMember(), event.getChannelLeft().getMembers());
    }

    @Override
    public void run() {
        voice.cleanup();
    }
}
