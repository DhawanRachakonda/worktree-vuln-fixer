package dev.intel.feed;

/** A feed item that cannot be stored (no identity, title or usable link). It is skipped and counted, not fatal. */
public class MalformedItemException extends Exception {

    private static final long serialVersionUID = 1L;

    public MalformedItemException(String reason) {
        super(reason);
    }

    public String reason() {
        return getMessage();
    }
}
