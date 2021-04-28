package de.chojo.repbot.manager;

import de.chojo.repbot.commands.Scan;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.internal.utils.CacheConsumer;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class MemberCacheManager implements MemberCachePolicy, Runnable {
    public static final int CACHE_DURATION = 15;
    private final HashMap<Long, Instant> seen = new HashMap<>();
    private final Scan scan;

    public MemberCacheManager(Scan scan) {
        this.scan = scan;
    }

    public void seen(Member member) {
        seen.put(member.getIdLong(), Instant.now());
    }

    @Override
    public boolean cacheMember(@NotNull Member member) {
        if (MemberCachePolicy.ONLINE.cacheMember(member)) {
            return true;
        }

        if (scan.isRunning(member.getGuild())) {
            return true;
        }

        if (!seen.containsKey(member.getIdLong())) {
            seen.put(member.getIdLong(), Instant.now());
            log.debug("Requested user for the first time. Caching for some time.");
            return false;
        }

        if (seen.get(member.getIdLong()).isBefore(oldest())) {
            var remove = seen.remove(member.getIdLong());
            log.debug("Removing {} from cache. Havent seen for {} minutes.", member.getIdLong(), remove.until(Instant.now(), ChronoUnit.MINUTES));
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        clean();
    }

    public synchronized void clean() {
        Set<Long> remove = new HashSet<>();
        var oldest = oldest();
        seen.forEach((key, value) -> {
            if (value.isBefore(oldest)) {
                remove.add(key);
            }
        });
        remove.forEach(seen::remove);
    }

    private Instant oldest() {
        return Instant.now().minus(CACHE_DURATION, ChronoUnit.MINUTES);
    }
}
