package de.chojo.repbot.commands.abuseprotection.handler;

import de.chojo.jdautil.wrapper.EventContext;

public interface BooleanMessageMapper {
    default String getBooleanMessage(EventContext context, boolean value, String whenTrue, String whenFalse) {
        return context.localize(value ? whenTrue : whenFalse);
    }
}
