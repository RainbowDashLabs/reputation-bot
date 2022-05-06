package de.chojo.repbot.dao.access.guild.settings.sub.thanking;

import de.chojo.jdautil.parsing.Verifier;
import de.chojo.repbot.dao.access.guild.settings.sub.Thanking;
import de.chojo.repbot.dao.components.GuildHolder;
import de.chojo.sqlutil.base.QueryFactoryHolder;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageReaction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class Reactions extends QueryFactoryHolder implements GuildHolder {
    private final Thanking thanking;
    private final Set<String> reactions;
    private String mainReaction;

    public Reactions(Thanking thanking, String mainReaction, Set<String> reactions) {
        super(thanking);
        this.thanking = thanking;
        this.mainReaction = mainReaction;
        this.reactions = reactions;
    }

    @Override
    public Guild guild() {
        return thanking.guild();
    }

    public boolean isReaction(MessageReaction.ReactionEmote reactionEmote) {
        if (reactionEmote.isEmoji()) {
            return isReaction(reactionEmote.getEmoji());
        }
        return isReaction(reactionEmote.getId());
    }

    private boolean isReaction(String reaction) {
        if (mainReaction.equals(reaction)) {
            return true;
        }
        return reactions.contains(reaction);
    }

    public boolean reactionIsEmote() {
        return Verifier.isValidId(mainReaction());
    }

    public Optional<String> reactionMention() {
        if (!reactionIsEmote()) {
            return Optional.ofNullable(mainReaction());
        }
        return Optional.of(guild().retrieveEmoteById(mainReaction()).onErrorFlatMap(n -> null).complete()).map(Emote::getAsMention);
    }

    public String mainReaction() {
        return mainReaction;
    }

    public List<String> getAdditionalReactionMentions() {
        return reactions.stream()
                .map(reaction -> {
                    if (Verifier.isValidId(reaction)) {
                        var asMention = guild().retrieveEmoteById(reaction).onErrorFlatMap(n -> null).complete();
                        return asMention == null ? null : asMention.getAsMention();
                    }
                    return reaction;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public boolean add(String reaction) {
        var result = builder().query("""
                        INSERT INTO guild_reactions(guild_id, reaction) VALUES (?,?)
                            ON CONFLICT(guild_id, reaction)
                                DO NOTHING;
                        """)
                             .paramsBuilder(stmt -> stmt.setLong(guildId()).setString(reaction))
                             .update()
                             .executeSync() > 0;
        if (result) {
            reactions.add(reaction);
        }
        return true;
    }

    public boolean remove(String reaction) {
        var result = builder().query("""
                        DELETE FROM guild_reactions WHERE guild_id = ? AND reaction = ?;
                        """)
                             .paramsBuilder(stmt -> stmt.setLong(guildId()).setString(reaction))
                             .update()
                             .executeSync() > 0;
        if (result) {
            reactions.remove(reaction);
        }

        return result;
    }

    public boolean mainReaction(String reaction) {
        var result = builder().query("""
                        INSERT INTO thank_settings(guild_id, reaction) VALUES (?,?)
                            ON CONFLICT(guild_id)
                                DO UPDATE
                                    SET reaction = excluded.reaction
                        """)
                             .paramsBuilder(stmt -> stmt.setLong(guildId()).setString(reaction))
                             .update()
                             .executeSync() > 0;
        if (result) {
            mainReaction = reaction;
        }
        return result;
    }

    public Set<String> reactions() {
        return reactions;
    }
}
