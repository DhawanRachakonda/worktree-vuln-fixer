package dev.intel.query;

/** A tool argument that is not acceptable. The message names the argument and says what is wrong. */
public class InvalidArgumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String argument;

    public InvalidArgumentException(String argument, String problem) {
        super("Invalid argument '" + argument + "': " + problem);
        this.argument = argument;
    }

    public String argument() {
        return argument;
    }
}
