package de.chojo.repbot.analyzer;

import de.chojo.jdautil.parsing.DiscordResolver;
import de.chojo.jdautil.parsing.WeightedEntry;
import de.chojo.repbot.util.ContextResolver;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class MessageAnalyzer {
    private static final int LOOKAROUND = 6;
    private static final Logger log = getLogger(MessageAnalyzer.class);
    private final ContextResolver contextResolver;

    public MessageAnalyzer(DataSource dataSource) {
        contextResolver = new ContextResolver(dataSource);
    }

    /**
     * Analyze a message.
     *
     * @param pattern       regex pattern for targetwords
     * @param message       message to analyze
     * @param maxHistoryAge max age of messages to find targets
     * @param limitTargets  true if targets should be limited to users which have written in the channel in the
     *                      maxHistoryAge
     * @param threshold     threshold for fuzzy matches
     * @param limit         limit for returned matches in the analyzer result
     * @return analyzer results
     */
    public AnalyzerResult processMessage(Pattern pattern, Message message, int maxHistoryAge, boolean limitTargets, double threshold, int limit) {
        if (pattern.pattern().isBlank()) return AnalyzerResult.noMatch();
        var contentRaw = message.getContentRaw();

        if (!pattern.matcher(contentRaw).find()) return AnalyzerResult.noMatch();
        if (message.getType() == MessageType.INLINE_REPLY) {

            var referencedMessage = message.getReferencedMessage();
            if (referencedMessage == null) return AnalyzerResult.noMatch();

            Member user;

            try {
                user = message.getGuild().retrieveMemberById(referencedMessage.getAuthor().getIdLong()).complete();
            } catch (RuntimeException e) {
                log.debug("Could not retrieve member. Probably not on guild anymore.");
                return AnalyzerResult.noMatch();
            }

            return AnalyzerResult.answer(message.getAuthor(), user, referencedMessage);
        }

        Set<Member> targets = Collections.emptySet();
        if (limitTargets) {
            targets = contextResolver.getCombinedContext(message, maxHistoryAge);
        }

        var mentionedMembers = message.getMentionedUsers();
        if (!mentionedMembers.isEmpty()) {
            if (mentionedMembers.size() > limit) {
                return resolveMessage(message, pattern, targets, threshold, limit);
            }

            List<Member> members = new ArrayList<>();

            for (var mentionedMember : mentionedMembers) {
                try {
                    members.add(message.getGuild().retrieveMemberById(mentionedMember.getIdLong()).complete());
                } catch (RuntimeException e) {
                    log.debug("Could not retrieve member. Probably not on guild anymore.");
                }
            }

            if (members.isEmpty()) return AnalyzerResult.noMatch();

            return AnalyzerResult.mention(message.getAuthor(), members);
        }
        return resolveMessage(message, pattern, targets, threshold, limit);
    }


    private AnalyzerResult resolveMessage(Message message, Pattern thankPattern, @NotNull Set<Member> targets, double threshold, int limit) {
        var contentRaw = message.getContentRaw();

        var words = new ArrayList<>(List.of(contentRaw.split("\\s")));
        words.removeIf(String::isBlank);

        List<Integer> thankWordIndices = new ArrayList<>();
        var i = 0;
        for (var word : words) {
            if (thankPattern.matcher(word).find()) {
                thankWordIndices.add(i);
            }
        }
        List<WeightedEntry<Member>> users = new ArrayList<>();

        for (var thankwordindex : thankWordIndices) {
            List<String> resolve = new ArrayList<>();

            if (thankwordindex != 0) {
                resolve.addAll(words.subList(Math.max(0, thankwordindex - LOOKAROUND), thankwordindex));
            }
            if (thankwordindex != words.size() - 1) {
                resolve.addAll(words.subList(Math.min(thankwordindex + 1, words.size() - 1), Math.min(words.size(), thankwordindex + LOOKAROUND + 1)));
            }

            for (var word : resolve) {
                List<WeightedEntry<Member>> weightedMembers;
                if (targets.isEmpty()) {
                    weightedMembers = DiscordResolver.fuzzyGuildTargetSearch(word, targets);
                } else {
                    weightedMembers = DiscordResolver.fuzzyGuildUserSearch(message.getGuild(), word);
                }
                if (weightedMembers.isEmpty()) continue;
                users.addAll(weightedMembers);
            }
        }

        var members = users.stream()
                .filter(e -> e.getWeight() >= threshold)
                .distinct()
                .sorted()
                .limit(limit)
                .collect(Collectors.toList());
        if (members.isEmpty()) return AnalyzerResult.noTarget(message.getAuthor());

        return AnalyzerResult.fuzzy(message.getAuthor(), members);
    }
}
