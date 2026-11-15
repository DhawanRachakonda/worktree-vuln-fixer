package dev.subscriber.api;

import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.SessionState;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only access to the session records: the list (newest first, filterable by state, paged), one session, and the events of one
 * session. There is no method that changes anything: every mapping is a GET, so any other method is answered with 405.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    static final int MAX_PAGE = 100_000;
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final FixSessionRepository sessions;

    public SessionController(FixSessionRepository sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public SessionPage list(@RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        SessionState filter = parseState(state);
        if (page < 0 || page > MAX_PAGE) {
            throw new BadRequest("page must be between 0 and " + MAX_PAGE);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BadRequest("size must be between 1 and " + MAX_SIZE);
        }
        List<SessionView> items = sessions.list(filter, size, page * size).stream().map(SessionView::of).toList();
        return new SessionPage(items, page, size, sessions.count(filter));
    }

    @GetMapping("/{id}")
    public SessionView one(@PathVariable String id) {
        return SessionView.of(find(id));
    }

    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable String id) {
        find(id);
        return sessions.events(id).stream().map(EventView::of).toList();
    }

    private FixSession find(String id) {
        // An id that is not a UUID cannot be a session; answer without asking the database.
        if (!SESSION_ID.matcher(id).matches()) {
            throw new NotFound();
        }
        return sessions.find(id).orElseThrow(NotFound::new);
    }

    private static SessionState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return SessionState.valueOf(state.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw new BadRequest("state must be one of " + Arrays.toString(SessionState.values()));
        }
    }

    /** The error body of every refusal. */
    public record ErrorBody(String error) {
    }

    static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message, null, false, false);
        }
    }

    static final class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotFound() {
            super("no such session", null, false, false);
        }
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<ErrorBody> badRequest(BadRequest e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody(e.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ErrorBody> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(e.getMessage()));
    }

    /** A page or size that is not a number. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> notANumber(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody(e.getName() + " must be a whole number"));
    }
}
