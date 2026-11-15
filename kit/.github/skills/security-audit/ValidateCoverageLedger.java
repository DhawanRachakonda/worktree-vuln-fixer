import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates coverage-ledger.json and its canonical coverage IDs.
 * Usage: java ValidateCoverageLedger.java <path-to-coverage-ledger.json>
 *
 * <p>A Java port of the vendored {@code validate-coverage-ledger.cjs} (see UPSTREAM.md), with the same rules, limits, messages, output and
 * exit codes. Needs JDK 25 (the other source files of this folder, {@code Json.java}, are compiled with it).
 */
public final class ValidateCoverageLedger {

    static final int INPUT_BYTES = 5 * 1024 * 1024;
    static final int UNITS = 10_000;
    static final int COLLECTION_ITEMS = 1000;
    static final int OBJECT_FIELDS = 1000;
    static final int NESTING_DEPTH = 64;
    static final int PREFLIGHT_VALUES = 500_000;
    static final int VALIDATION_ERRORS = 100;
    private static final int MAX_TEXT_LENGTH = 4096;

    private static final List<String> REQUIRED_FIELDS = List.of("coverage_id", "canonical_refs", "surface", "boundary", "subsystem",
            "attack_class", "starting_paths", "ordinary_attack_class_block", "selected_companion_blocks", "excluded_blocks", "prior_status",
            "attempts", "wave", "status", "agent_id", "reviewed_paths", "local_checks", "result_fingerprints", "unresolved");
    private static final List<String> REF_FIELDS = List.of("surface", "boundary", "subsystem", "attack_class");
    private static final Set<String> STATUSES = Set.of("planned", "not_applicable", "out_of_scope", "in_progress", "covered", "candidate",
            "blocked", "deferred");
    private static final Set<String> ATTEMPT_STATUSES = Set.of("covered", "candidate", "blocked");
    private static final List<String> ATTEMPT_FIELDS = List.of("wave", "status", "agent_id", "reviewed_paths", "local_checks",
            "result_fingerprints", "unresolved", "reassignment_reason");
    private static final Set<String> PRIOR_STATUSES = Set.of("new", "prior_confirmed_same_source", "prior_confirmed_changed_source",
            "prior_needs_validation", "prior_deferred", "prior_blocked", "prior_out_of_scope", "prior_covered_same_source",
            "prior_covered_changed_source", "prior_rejected_claim_changed", "none");
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@+-]*\\z");
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}\\z");
    private static final Pattern WINDOWS_RESERVED_AGENT_ID = Pattern.compile("^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])\\z");
    private static final Pattern WINDOWS_RESERVED_COMPONENT = Pattern.compile(
            "^(?:con|prn|aux|nul|clock\\$|conin\\$|conout\\$|com[1-9\\u00b9\\u00b2\\u00b3]|lpt[1-9\\u00b9\\u00b2\\u00b3])(?:\\.|\\z)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ValidateCoverageLedger() {
    }

    /** Every message is made safe on the way in, and no more than {@link #VALIDATION_ERRORS} are kept. */
    static final class Errors {
        final List<String> list = new ArrayList<>();

        int size() {
            return list.size();
        }

        void push(String message) {
            if (list.size() < VALIDATION_ERRORS) {
                list.add(Json.escapeUnsafe(message));
            }
        }

        void pushAll(List<String> messages) {
            for (String message : messages) {
                push(message);
            }
        }
    }

    private static final class JsonStructureError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonStructureError(String message) {
            super(message, null, false, false);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The text before it is parsed
    // ---------------------------------------------------------------------------------------------------------------

    private static final class Container {
        final boolean array;
        final boolean topLevel;
        String state;
        int items;
        int fields;

        Container(boolean array, boolean topLevel) {
            this.array = array;
            this.topLevel = topLevel;
            this.state = array ? "firstValueOrEnd" : "firstKeyOrEnd";
        }
    }

    /** Checks the shape and the size of the JSON text without building the document. */
    static void preflightJsonText(String contents) {
        ArrayDeque<Container> containers = new ArrayDeque<>();
        String[] rootState = {"value"};
        boolean inString = false;
        boolean escaped = false;
        int[] totalValues = {0};

        for (int index = 0; index < contents.length(); index++) {
            char character = contents.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (isWhitespace(character)) {
                continue;
            }
            if (character == '"') {
                Container container = containers.peek();
                if (container != null && !container.array && (container.state.equals("firstKeyOrEnd") || container.state.equals("key"))) {
                    container.fields++;
                    if (container.fields > OBJECT_FIELDS) {
                        throw fail("exceeds " + OBJECT_FIELDS + " field object limit");
                    }
                    container.state = "colon";
                } else {
                    beginValue(containers, rootState, totalValues);
                }
                inString = true;
            } else if (character == '[' || character == '{') {
                beginValue(containers, rootState, totalValues);
                if (containers.size() >= NESTING_DEPTH) {
                    throw fail("exceeds nesting depth limit " + NESTING_DEPTH);
                }
                containers.push(new Container(character == '[', containers.isEmpty()));
            } else if (character == ']' || character == '}') {
                boolean array = character == ']';
                Container container = containers.peek();
                if (container == null || container.array != array) {
                    throw fail("has mismatched JSON containers");
                }
                boolean canClose = array
                        ? container.state.equals("firstValueOrEnd") || container.state.equals("commaOrEnd")
                        : container.state.equals("firstKeyOrEnd") || container.state.equals("commaOrEnd");
                if (!canClose) {
                    throw fail("has malformed JSON " + (array ? "array" : "object") + " structure");
                }
                containers.pop();
            } else if (character == ':') {
                Container container = containers.peek();
                if (container == null || container.array || !container.state.equals("colon")) {
                    throw fail("has malformed JSON object structure");
                }
                container.state = "value";
            } else if (character == ',') {
                Container container = containers.peek();
                if (container == null || !container.state.equals("commaOrEnd")) {
                    throw fail("has malformed JSON collection structure");
                }
                container.state = container.array ? "value" : "key";
            } else {
                beginValue(containers, rootState, totalValues);
                while (index + 1 < contents.length() && !isTokenDelimiter(contents.charAt(index + 1))) {
                    index++;
                }
            }
        }
        if (inString) {
            throw fail("has an unterminated JSON string");
        }
        if (!containers.isEmpty()) {
            throw fail("has truncated JSON structure");
        }
        if (!rootState[0].equals("end")) {
            throw fail("has no JSON value");
        }
    }

    private static JsonStructureError fail(String message) {
        return new JsonStructureError("input " + message);
    }

    private static void beginValue(ArrayDeque<Container> containers, String[] rootState, int[] totalValues) {
        Container container = containers.peek();
        if (container == null) {
            if (!rootState[0].equals("value")) {
                throw fail("has malformed JSON structure");
            }
            rootState[0] = "end";
        } else if (container.array) {
            if (!container.state.equals("firstValueOrEnd") && !container.state.equals("value")) {
                throw fail("has malformed JSON array structure");
            }
            container.items++;
            int limit = container.topLevel ? UNITS : COLLECTION_ITEMS;
            if (container.items > limit) {
                throw fail(container.topLevel ? "exceeds " + limit + " top-level unit limit" : "exceeds " + limit + " item array limit");
            }
            container.state = "commaOrEnd";
        } else {
            if (!container.state.equals("value")) {
                throw fail("has malformed JSON object structure");
            }
            container.state = "commaOrEnd";
        }
        totalValues[0]++;
        if (totalValues[0] > PREFLIGHT_VALUES) {
            throw fail("exceeds " + PREFLIGHT_VALUES + " total value limit");
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isTokenDelimiter(char c) {
        return isWhitespace(c) || c == ',' || c == ':' || c == '[' || c == ']' || c == '{' || c == '}' || c == '"';
    }

    private record Frame(Object value, int depth, String location) {
    }

    /** The limits of the parsed document: values, depth, entries and fields. */
    static List<String> preflightDocument(Object root) {
        Errors errors = new Errors();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, 0, "$"));
        int visited = 0;
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            visited++;
            if (visited > PREFLIGHT_VALUES) {
                errors.push("$: exceeds " + PREFLIGHT_VALUES + " total values");
                return errors.list;
            }
            if (!(frame.value instanceof Map || frame.value instanceof List)) {
                continue;
            }
            if (frame.depth >= NESTING_DEPTH) {
                errors.push(frame.location + ": exceeds nesting depth limit " + NESTING_DEPTH);
                return errors.list;
            }
            if (frame.value instanceof List<?> list) {
                int limit = frame.location.equals("$") ? UNITS : COLLECTION_ITEMS;
                if (list.size() > limit) {
                    errors.push(frame.location + ": exceeds " + limit + " entries");
                    return errors.list;
                }
                for (int index = list.size() - 1; index >= 0; index--) {
                    stack.push(new Frame(list.get(index), frame.depth + 1, frame.location + "[" + index + "]"));
                }
                continue;
            }
            Map<String, Object> map = Json.asObject(frame.value);
            List<String> keys = Json.keys(map);
            if (keys.size() > OBJECT_FIELDS) {
                errors.push(frame.location + ": exceeds " + OBJECT_FIELDS + " object fields");
                return errors.list;
            }
            for (int index = keys.size() - 1; index >= 0; index--) {
                stack.push(new Frame(map.get(keys.get(index)), frame.depth + 1, frame.location + "{" + index + "}"));
            }
        }
        return errors.list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Text and reference checks
    // ---------------------------------------------------------------------------------------------------------------

    private static boolean isVisibleText(Object value) {
        return isVisibleText(value, MAX_TEXT_LENGTH);
    }

    private static boolean isVisibleText(Object value, int maxLength) {
        return value instanceof String text && !text.isEmpty() && text.length() <= maxLength && Json.jsTrim(text).equals(text)
                && Json.hasVisibleProse(text);
    }

    private static boolean isCanonicalRef(Object value) {
        return isVisibleText(value, 1024) && !Json.anyPathForbidden((String) value) && Json.normalizeNfc((String) value).equals(value);
    }

    private static String encodeCanonicalRef(Object value) {
        if (!isCanonicalRef(value)) {
            throw new IllegalArgumentException("invalid canonical reference");
        }
        StringBuilder encoded = new StringBuilder();
        for (byte raw : ((String) value).getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xff;
            boolean unreserved = (b >= 0x41 && b <= 0x5a) || (b >= 0x61 && b <= 0x7a) || (b >= 0x30 && b <= 0x39) || b == 0x2d || b == 0x2e
                    || b == 0x5f || b == 0x7e;
            encoded.append(unreserved ? String.valueOf((char) b) : "%%%02X".formatted(b));
        }
        return encoded.toString();
    }

    private static String canonicalCoverageId(Object refs) {
        if (!Json.isObject(refs)) {
            throw new IllegalArgumentException("canonical_refs must be an object");
        }
        Map<String, Object> map = Json.asObject(refs);
        List<String> fields = new ArrayList<>(REF_FIELDS);
        if (map.containsKey("lifecycle")) {
            fields.add("lifecycle");
        }
        if (map.size() != fields.size() || fields.stream().anyMatch(field -> !map.containsKey(field))) {
            throw new IllegalArgumentException("canonical_refs has missing or unexpected fields");
        }
        List<String> parts = new ArrayList<>();
        for (String field : fields) {
            parts.add(encodeCanonicalRef(map.get(field)));
        }
        return String.join("::", parts);
    }

    static boolean isSafeRelativePath(Object value) {
        if (!(value instanceof String text) || text.isEmpty() || !Json.hasValidUnicodeScalarValues(text) || !Json.jsTrim(text).equals(text)
                || Json.anyPathForbidden(text) || text.contains("\\") || text.contains(":")) {
            return false;
        }
        if (text.startsWith("/") || text.startsWith("~")) {
            return false;
        }
        for (String segment : text.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.endsWith(" ") || segment.endsWith(".")
                    || WINDOWS_RESERVED_COMPONENT.matcher(segment).find()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeAgentId(Object value) {
        return value instanceof String text && AGENT_ID_PATTERN.matcher(text).find() && !WINDOWS_RESERVED_AGENT_ID.matcher(text).find();
    }

    private static boolean isOwnedArtifactPath(Object value, Object agentId) {
        if (!isSafeAgentId(agentId) || !isSafeRelativePath(value)) {
            return false;
        }
        String prefix = "agents/" + agentId + "/artifacts/";
        String text = (String) value;
        return text.startsWith(prefix) && text.length() > prefix.length();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The units
    // ---------------------------------------------------------------------------------------------------------------

    private static void validateStringArray(Object value, String location, Errors errors, boolean allowEmpty, boolean fingerprint,
            boolean pathValue) {
        if (!Json.isArray(value)) {
            errors.push(location + ": expected array");
            return;
        }
        List<Object> list = Json.asArray(value);
        if (!allowEmpty && list.isEmpty()) {
            errors.push(location + ": must not be empty");
        }
        if (list.size() > COLLECTION_ITEMS) {
            errors.push(location + ": exceeds " + COLLECTION_ITEMS + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < Math.min(list.size(), COLLECTION_ITEMS); index++) {
            Object entry = list.get(index);
            String entryLocation = location + "[" + index + "]";
            boolean valid = pathValue ? isSafeRelativePath(entry) : isVisibleText(entry);
            if (!valid) {
                errors.push(entryLocation + ": invalid " + (pathValue ? "repository-relative path" : "text"));
            }
            if (fingerprint && entry instanceof String text && !FINGERPRINT_PATTERN.matcher(text).find()) {
                errors.push(entryLocation + ": invalid fingerprint");
            }
            if (entry instanceof String text) {
                if (seen.contains(text)) {
                    errors.push(entryLocation + ": duplicate entry");
                }
                seen.add(text);
            }
        }
    }

    private static void validateChecks(Object value, String location, Errors errors) {
        if (!Json.isArray(value)) {
            errors.push(location + ": expected array");
            return;
        }
        List<Object> list = Json.asArray(value);
        if (list.size() > COLLECTION_ITEMS) {
            errors.push(location + ": exceeds " + COLLECTION_ITEMS + " entries");
        }
        for (int index = 0; index < Math.min(list.size(), COLLECTION_ITEMS); index++) {
            Object check = list.get(index);
            String base = location + "[" + index + "]";
            if (!Json.isObject(check)) {
                errors.push(base + ": expected object");
                continue;
            }
            for (String field : List.of("agent_id", "reviewed_paths", "invariant", "method", "result", "artifact")) {
                if (!Json.hasOwn(check, field)) {
                    errors.push(base + ": missing required field " + Json.safeQuote(field));
                }
            }
            Object agentId = Json.get(check, "agent_id");
            Object method = Json.get(check, "method");
            Object artifact = Json.get(check, "artifact");
            if (!isSafeAgentId(agentId)) {
                errors.push(base + ".agent_id: expected a canonical lowercase agent ID");
            }
            validateStringArray(Json.get(check, "reviewed_paths"), base + ".reviewed_paths", errors, false, false, true);
            if (!isVisibleText(Json.get(check, "invariant"))) {
                errors.push(base + ".invariant: invalid text");
            }
            boolean isSource = "source".equals(method);
            boolean isLocal = "local".equals(method);
            if (!isSource && !isLocal) {
                errors.push(base + ".method: expected \"source\" or \"local\"");
            }
            if (!isVisibleText(Json.get(check, "result"))) {
                errors.push(base + ".result: invalid text");
            }
            if (isSource && artifact != Json.NULL) {
                errors.push(base + ".artifact: source-only check must use null");
            } else if (isLocal) {
                if (!isOwnedArtifactPath(artifact, agentId)) {
                    errors.push(base + ".artifact: local check requires an artifact owned by agent "
                            + Json.safeQuote(isSafeAgentId(agentId) ? agentId : "<agent-id>"));
                }
            } else if (!isSource && !isLocal && artifact != Json.NULL && !isSafeRelativePath(artifact)) {
                errors.push(base + ".artifact: expected null or a safe output-relative path");
            }
        }
    }

    private static void validateReviewedPathOwnership(Object unit, String base, Errors errors) {
        Object reviewed = Json.get(unit, "reviewed_paths");
        Object checks = Json.get(unit, "local_checks");
        if (!Json.isArray(reviewed) || !Json.isArray(checks)) {
            return;
        }
        Set<String> aggregatePaths = new java.util.LinkedHashSet<>();
        for (Object value : Json.asArray(reviewed)) {
            if (value instanceof String text) {
                aggregatePaths.add(text);
            }
        }
        Set<String> ownedPaths = new java.util.LinkedHashSet<>();
        for (Object check : Json.asArray(checks)) {
            if (!Json.isObject(check) || !Json.isArray(Json.get(check, "reviewed_paths"))) {
                continue;
            }
            for (Object path : Json.asArray(Json.get(check, "reviewed_paths"))) {
                if (path instanceof String text) {
                    ownedPaths.add(text);
                }
            }
        }
        for (String path : aggregatePaths) {
            if (!ownedPaths.contains(path)) {
                errors.push(base + ".reviewed_paths: " + Json.safeQuote(path) + " has no check owner");
            }
        }
        for (String path : ownedPaths) {
            if (!aggregatePaths.contains(path)) {
                errors.push(base + ".local_checks: owned path " + Json.safeQuote(path) + " is absent from aggregate reviewed_paths");
            }
        }
    }

    private static void validateExcludedBlocks(Object value, String location, Errors errors) {
        if (!Json.isArray(value)) {
            errors.push(location + ": expected array");
            return;
        }
        List<Object> list = Json.asArray(value);
        if (list.size() > COLLECTION_ITEMS) {
            errors.push(location + ": exceeds " + COLLECTION_ITEMS + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < Math.min(list.size(), COLLECTION_ITEMS); index++) {
            Object entry = list.get(index);
            String base = location + "[" + index + "]";
            if (!Json.isObject(entry)) {
                errors.push(base + ": expected object");
                continue;
            }
            if (!isVisibleText(Json.get(entry, "block"))) {
                errors.push(base + ".block: invalid text");
            }
            if (!isVisibleText(Json.get(entry, "reason"))) {
                errors.push(base + ".reason: invalid text");
            }
            if (Json.get(entry, "block") instanceof String block) {
                if (seen.contains(block)) {
                    errors.push(base + ".block: duplicate entry");
                }
                seen.add(block);
            }
        }
    }

    private static String semanticKey(Object unit) {
        String lifecycle = Json.hasOwn(unit, "lifecycle") ? Json.stringify((String) Json.get(unit, "lifecycle")) : "null";
        return "[" + Json.stringify((String) Json.get(unit, "surface")) + "," + Json.stringify((String) Json.get(unit, "boundary")) + ","
                + Json.stringify((String) Json.get(unit, "subsystem")) + "," + Json.stringify((String) Json.get(unit, "attack_class")) + ","
                + lifecycle + "]";
    }

    private static boolean hasValidSemanticFields(Object unit) {
        return REF_FIELDS.stream().allMatch(field -> isVisibleText(Json.get(unit, field)))
                && (!Json.hasOwn(unit, "lifecycle") || isVisibleText(Json.get(unit, "lifecycle")));
    }

    private static void requireEmptyArray(Object unit, String field, String base, Errors errors) {
        Object value = Json.get(unit, field);
        if (Json.isArray(value) && !Json.asArray(value).isEmpty()) {
            errors.push(base + "." + field + ": unit with status " + Json.safeQuote(Json.get(unit, "status")) + " must keep this array empty");
        }
    }

    private static void requireNonemptyArray(Object unit, String field, String base, Errors errors) {
        Object value = Json.get(unit, field);
        if (!Json.isArray(value) || Json.asArray(value).isEmpty()) {
            errors.push(base + "." + field + ": unit with status " + Json.safeQuote(Json.get(unit, "status")) + " requires entries");
        }
    }

    private static void validateStateInvariants(Object unit, String base, Errors errors) {
        Object status = Json.get(unit, "status");
        if (!"candidate".equals(status)) {
            requireEmptyArray(unit, "result_fingerprints", base, errors);
        }
        if (!(status instanceof String name)) {
            return;
        }
        switch (name) {
            case "planned" -> {
                if (Json.get(unit, "agent_id") != Json.NULL) {
                    errors.push(base + ".agent_id: planned unit must be unassigned");
                }
                emptyEvidence(unit, base, errors);
                requireEmptyArray(unit, "unresolved", base, errors);
            }
            case "not_applicable", "out_of_scope", "deferred" -> {
                if (Json.get(unit, "agent_id") != Json.NULL) {
                    errors.push(base + ".agent_id: unit with status " + Json.safeQuote(status) + " must be unassigned");
                }
                emptyEvidence(unit, base, errors);
                requireNonemptyArray(unit, "unresolved", base, errors);
            }
            case "in_progress" -> {
                requireOwner(unit, base, errors);
                emptyEvidence(unit, base, errors);
                requireEmptyArray(unit, "unresolved", base, errors);
            }
            case "blocked" -> {
                requireOwner(unit, base, errors);
                requireNonemptyArray(unit, "reviewed_paths", base, errors);
                requireNonemptyArray(unit, "local_checks", base, errors);
                requireNonemptyArray(unit, "unresolved", base, errors);
            }
            case "covered" -> {
                requireOwner(unit, base, errors);
                requireNonemptyArray(unit, "reviewed_paths", base, errors);
                requireNonemptyArray(unit, "local_checks", base, errors);
                requireEmptyArray(unit, "unresolved", base, errors);
            }
            case "candidate" -> {
                requireOwner(unit, base, errors);
                requireNonemptyArray(unit, "reviewed_paths", base, errors);
                requireNonemptyArray(unit, "local_checks", base, errors);
                requireNonemptyArray(unit, "result_fingerprints", base, errors);
            }
            default -> {
            }
        }
    }

    private static void emptyEvidence(Object unit, String base, Errors errors) {
        requireEmptyArray(unit, "reviewed_paths", base, errors);
        requireEmptyArray(unit, "local_checks", base, errors);
    }

    private static void requireOwner(Object unit, String base, Errors errors) {
        if (!isSafeAgentId(Json.get(unit, "agent_id"))) {
            errors.push(base + ".agent_id: unit with status " + Json.safeQuote(Json.get(unit, "status"))
                    + " requires a canonical lowercase agent ID");
        }
    }

    private static boolean contains(Set<String> set, Object value) {
        return value instanceof String text && set.contains(text);
    }

    private static void validateAttempts(Object value, Object unit, String base, Errors errors) {
        if (!Json.isArray(value)) {
            errors.push(base + ".attempts: expected array");
            return;
        }
        List<Object> attempts = Json.asArray(value);
        if (attempts.size() > COLLECTION_ITEMS) {
            errors.push(base + ".attempts: exceeds " + COLLECTION_ITEMS + " entries");
        }
        Set<String> priorOwners = new HashSet<>();
        Set<String> priorArtifacts = new HashSet<>();
        double previousWave = 0;
        Object unitWave = Json.get(unit, "wave");
        for (int index = 0; index < Math.min(attempts.size(), COLLECTION_ITEMS); index++) {
            Object attempt = attempts.get(index);
            String attemptBase = base + ".attempts[" + index + "]";
            if (!Json.isObject(attempt)) {
                errors.push(attemptBase + ": expected object");
                continue;
            }
            for (String field : ATTEMPT_FIELDS) {
                if (!Json.hasOwn(attempt, field)) {
                    errors.push(attemptBase + ": missing required field " + Json.safeQuote(field));
                }
            }
            Object wave = Json.get(attempt, "wave");
            if (!Json.isInteger(wave) || (Double) wave < 1) {
                errors.push(attemptBase + ".wave: expected a positive integer");
            } else {
                double number = (Double) wave;
                if (number <= previousWave) {
                    errors.push(attemptBase + ".wave: archived attempt waves must be strictly increasing");
                }
                if (Json.isInteger(unitWave) && number >= (Double) unitWave) {
                    errors.push(attemptBase + ".wave: archived attempt wave must precede current wave " + Json.safeQuote(unitWave));
                }
                previousWave = number;
            }
            Object status = Json.get(attempt, "status");
            if (!(status instanceof String s && ATTEMPT_STATUSES.contains(s))) {
                errors.push(attemptBase + ".status: expected \"covered\", \"candidate\", or \"blocked\"");
            }
            Object agentId = Json.get(attempt, "agent_id");
            boolean hasFreshOwner = false;
            if (!isSafeAgentId(agentId)) {
                errors.push(attemptBase + ".agent_id: archived attempt requires a canonical lowercase agent ID");
            } else if (priorOwners.contains((String) agentId)) {
                errors.push(attemptBase + ".agent_id: assignment owner must be fresh for each attempt");
            } else {
                hasFreshOwner = true;
            }
            validateStringArray(Json.get(attempt, "reviewed_paths"), attemptBase + ".reviewed_paths", errors, true, false, true);
            validateChecks(Json.get(attempt, "local_checks"), attemptBase + ".local_checks", errors);
            validateReviewedPathOwnership(attempt, attemptBase, errors);
            validateStringArray(Json.get(attempt, "result_fingerprints"), attemptBase + ".result_fingerprints", errors, true, true, false);
            validateStringArray(Json.get(attempt, "unresolved"), attemptBase + ".unresolved", errors, true, false, false);
            if (!isVisibleText(Json.get(attempt, "reassignment_reason"))) {
                errors.push(attemptBase + ".reassignment_reason: invalid text");
            }
            validateStateInvariants(attempt, attemptBase, errors);

            Object checks = Json.get(attempt, "local_checks");
            if (Json.isArray(checks)) {
                List<Object> list = Json.asArray(checks);
                for (int checkIndex = 0; checkIndex < list.size(); checkIndex++) {
                    Object check = list.get(checkIndex);
                    if (!Json.isObject(check)) {
                        continue;
                    }
                    if (contains(priorOwners, Json.get(check, "agent_id"))) {
                        errors.push(attemptBase + ".local_checks[" + checkIndex
                                + "].agent_id: prior assignment owner evidence must remain in its earlier attempt");
                    }
                    Object artifact = Json.get(check, "artifact");
                    if (contains(priorArtifacts, artifact)) {
                        errors.push(attemptBase + ".local_checks[" + checkIndex
                                + "].artifact: artifact from an earlier attempt cannot be reused");
                    }
                    if ("local".equals(Json.get(check, "method")) && artifact instanceof String text) {
                        priorArtifacts.add(text);
                    }
                }
            }
            if (hasFreshOwner) {
                priorOwners.add((String) agentId);
            }
        }

        Object unitAgent = Json.get(unit, "agent_id");
        if (isSafeAgentId(unitAgent) && priorOwners.contains((String) unitAgent)) {
            errors.push(base + ".agent_id: current assignment owner must be fresh after reassignment");
        }
        Object unitChecks = Json.get(unit, "local_checks");
        if (Json.isArray(unitChecks)) {
            List<Object> list = Json.asArray(unitChecks);
            for (int index = 0; index < list.size(); index++) {
                Object check = list.get(index);
                if (!Json.isObject(check)) {
                    continue;
                }
                if (contains(priorOwners, Json.get(check, "agent_id"))) {
                    errors.push(base + ".local_checks[" + index + "].agent_id: prior assignment owner evidence must remain in its archived attempt");
                }
                if (contains(priorArtifacts, Json.get(check, "artifact"))) {
                    errors.push(base + ".local_checks[" + index + "].artifact: artifact from an archived attempt cannot be reused");
                }
            }
        }
    }

    private static List<String> collectUnitErrors(Object unit, int index) {
        Errors errors = new Errors();
        String base = "$[" + index + "]";
        if (!Json.isObject(unit)) {
            return List.of(base + ": expected object");
        }
        for (String field : REQUIRED_FIELDS) {
            if (!Json.hasOwn(unit, field)) {
                errors.push(base + ": missing required field " + Json.safeQuote(field));
            }
        }
        for (String field : REF_FIELDS) {
            if (!isVisibleText(Json.get(unit, field))) {
                errors.push(base + "." + field + ": invalid text");
            }
        }
        if (Json.hasOwn(unit, "lifecycle") && !isVisibleText(Json.get(unit, "lifecycle"))) {
            errors.push(base + ".lifecycle: invalid text");
        }

        String expectedId = null;
        Object refs = Json.get(unit, "canonical_refs");
        if (!Json.isObject(refs)) {
            errors.push(base + ".canonical_refs: expected object");
        } else {
            Map<String, Object> refMap = Json.asObject(refs);
            List<String> expectedFields = new ArrayList<>(REF_FIELDS);
            if (refMap.containsKey("lifecycle")) {
                expectedFields.add("lifecycle");
            }
            for (String field : expectedFields) {
                if (!refMap.containsKey(field)) {
                    errors.push(base + ".canonical_refs: missing required field " + Json.safeQuote(field));
                } else if (!isCanonicalRef(refMap.get(field))) {
                    errors.push(base + ".canonical_refs." + field + ": invalid canonical reference");
                }
            }
            if (Json.keys(refMap).stream().anyMatch(field -> !expectedFields.contains(field))) {
                errors.push(base + ".canonical_refs: contains unexpected fields");
            }
            if (Json.hasOwn(unit, "lifecycle") != refMap.containsKey("lifecycle")) {
                errors.push(base + ": lifecycle and canonical_refs.lifecycle must appear together");
            }
            try {
                expectedId = canonicalCoverageId(refs);
            } catch (RuntimeException _) {
                // The specific reference errors above are more useful.
            }
        }
        Object coverageId = Json.get(unit, "coverage_id");
        if (!isVisibleText(coverageId, 65536)) {
            errors.push(base + ".coverage_id: invalid text");
        } else if (expectedId != null && !coverageId.equals(expectedId)) {
            errors.push(base + ".coverage_id: expected canonical ID " + Json.safeQuote(expectedId));
        }

        validateStringArray(Json.get(unit, "starting_paths"), base + ".starting_paths", errors, false, false, true);
        Object ordinary = Json.get(unit, "ordinary_attack_class_block");
        if (ordinary != Json.NULL && !isVisibleText(ordinary)) {
            errors.push(base + ".ordinary_attack_class_block: expected null or non-empty text");
        }
        validateStringArray(Json.get(unit, "selected_companion_blocks"), base + ".selected_companion_blocks", errors, true, false, false);
        validateExcludedBlocks(Json.get(unit, "excluded_blocks"), base + ".excluded_blocks", errors);
        Object companions = Json.get(unit, "selected_companion_blocks");
        Object excluded = Json.get(unit, "excluded_blocks");
        if (Json.isArray(companions) && Json.isArray(excluded)) {
            Set<String> selected = new HashSet<>();
            for (Object entry : Json.asArray(companions)) {
                if (entry instanceof String text) {
                    selected.add(text);
                }
            }
            List<Object> list = Json.asArray(excluded);
            for (int blockIndex = 0; blockIndex < list.size(); blockIndex++) {
                Object entry = list.get(blockIndex);
                if (Json.isObject(entry) && contains(selected, Json.get(entry, "block"))) {
                    errors.push(base + ".excluded_blocks[" + blockIndex + "].block: block is also selected");
                }
            }
        }

        Object prior = Json.get(unit, "prior_status");
        if (!(prior instanceof String p && PRIOR_STATUSES.contains(p))) {
            errors.push(base + ".prior_status: invalid value " + Json.safeQuote(prior));
        }
        Object status = Json.get(unit, "status");
        if (!(status instanceof String s && STATUSES.contains(s))) {
            errors.push(base + ".status: invalid value " + Json.safeQuote(status));
        }
        Object wave = Json.get(unit, "wave");
        if (!Json.isInteger(wave) || (Double) wave < 1) {
            errors.push(base + ".wave: expected a positive integer");
        }
        Object agentId = Json.get(unit, "agent_id");
        if (agentId != Json.NULL && !isSafeAgentId(agentId)) {
            errors.push(base + ".agent_id: expected null or a safe agent ID");
        }

        validateAttempts(Json.get(unit, "attempts"), unit, base, errors);
        validateStringArray(Json.get(unit, "reviewed_paths"), base + ".reviewed_paths", errors, true, false, true);
        validateChecks(Json.get(unit, "local_checks"), base + ".local_checks", errors);
        validateReviewedPathOwnership(unit, base, errors);
        validateStringArray(Json.get(unit, "result_fingerprints"), base + ".result_fingerprints", errors, true, true, false);
        validateStringArray(Json.get(unit, "unresolved"), base + ".unresolved", errors, true, false, false);

        validateStateInvariants(unit, base, errors);
        return errors.list;
    }

    private record Seen(int index, String key) {
    }

    private record SemanticOwner(String id, int index) {
    }

    static List<String> validateDocument(Object ledger) {
        Errors errors = new Errors();
        if (!Json.isArray(ledger)) {
            errors.push("$: expected a top-level array");
            return errors.list;
        }
        List<Object> units = Json.asArray(ledger);
        if (units.size() > UNITS) {
            errors.push("$: exceeds " + UNITS + " coverage units");
            return errors.list;
        }
        errors.pushAll(preflightDocument(ledger));
        if (errors.size() > 0) {
            return errors.list;
        }

        Map<String, Seen> ids = new HashMap<>();
        Map<String, SemanticOwner> semantics = new HashMap<>();
        String previousId = null;
        for (int index = 0; index < units.size() && errors.size() < VALIDATION_ERRORS; index++) {
            Object unit = units.get(index);
            errors.pushAll(collectUnitErrors(unit, index));
            if (errors.size() >= VALIDATION_ERRORS) {
                break;
            }
            if (!Json.isObject(unit) || !(Json.get(unit, "coverage_id") instanceof String coverageId)) {
                continue;
            }
            String key = hasValidSemanticFields(unit) ? semanticKey(unit) : null;
            Seen previous = ids.get(coverageId);
            if (previous != null) {
                String qualifier = key != null && previous.key != null && !previous.key.equals(key)
                        ? "canonical identity collision with different semantic fields"
                        : "duplicate coverage ID";
                errors.push("$[" + index + "].coverage_id: " + qualifier + " at $[" + previous.index + "]");
            } else {
                ids.put(coverageId, new Seen(index, key));
            }
            SemanticOwner owner = key == null ? null : semantics.get(key);
            if (owner != null && !owner.id.equals(coverageId)) {
                errors.push("$[" + index + "].canonical_refs: semantic tuple already uses coverage ID " + Json.safeQuote(owner.id) + " at $["
                        + owner.index + "]");
            } else if (key != null) {
                semantics.put(key, new SemanticOwner(coverageId, index));
            }
            if (previousId != null && previousId.compareTo(coverageId) > 0) {
                errors.push("$[" + index + "].coverage_id: units must be sorted lexicographically");
            }
            previousId = coverageId;
        }
        return errors.list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The program
    // ---------------------------------------------------------------------------------------------------------------

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("Usage: java ValidateCoverageLedger.java <path-to-coverage-ledger.json>");
            return 1;
        }
        String contents;
        try {
            contents = Json.readWithinLimit(Path.of(args[0]), INPUT_BYTES);
        } catch (Json.SafeInputError e) {
            err.println("Failed to read coverage ledger: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            err.println("Failed to read coverage ledger: input could not be opened or read safely");
            return 1;
        }

        try {
            preflightJsonText(contents);
        } catch (JsonStructureError e) {
            err.println("Failed to parse coverage ledger: " + e.getMessage());
            return 1;
        }
        Object ledger;
        try {
            ledger = Json.parse(contents);
        } catch (Json.SyntaxError e) {
            err.println("Failed to parse coverage ledger: invalid JSON syntax");
            return 1;
        }

        List<String> errors;
        try {
            errors = validateDocument(ledger);
        } catch (RuntimeException e) {
            err.println("Failed to validate coverage ledger: unexpected validation error");
            return 1;
        }
        for (String message : errors) {
            err.println("ERROR: " + message);
        }
        if (!errors.isEmpty()) {
            String cap = errors.size() == VALIDATION_ERRORS ? "; output capped at " + VALIDATION_ERRORS : "";
            err.println("FAIL: " + errors.size() + " validation error(s)" + cap);
            return 1;
        }
        out.println("PASS: " + Json.asArray(ledger).size() + " coverage units valid");
        return 0;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
