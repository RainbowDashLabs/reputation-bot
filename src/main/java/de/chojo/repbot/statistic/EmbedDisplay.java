package de.chojo.repbot.statistic;

import net.dv8tion.jda.api.EmbedBuilder;

public interface EmbedDisplay {
    void appendTo(EmbedBuilder embedBuilder);
}
