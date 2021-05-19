package de.chojo.repbot.data.wrapper;

import de.chojo.repbot.analyzer.ThankType;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ReputationLogEntry {
    private static final String PATH = "https://discord.com/channels/%s/%s/%s";

    private final long guildId;
    private final long channel_id;
    private final long donorId;
    private final long receiverId;
    private final long messageId;
    private final long refMessageId;
    private final ThankType type;

    public String getMessageJumpLink() {
        return String.format(PATH, guildId, channel_id, messageId);
    }

    public String getRedMessageJumpLink() {
        return String.format(PATH, guildId, channel_id, refMessageId);
    }

    public boolean hasRefMessage() {
        return refMessageId != 0;
    }
}
