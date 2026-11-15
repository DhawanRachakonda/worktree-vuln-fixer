package dev.subscriber.api;

import java.util.List;

/**
 * One page of sessions, newest first.
 *
 * @param page  the page number, starting at 0
 * @param size  the page size asked for
 * @param total how many sessions match the filter in all
 */
public record SessionPage(List<SessionView> items, int page, int size, long total) {
}
