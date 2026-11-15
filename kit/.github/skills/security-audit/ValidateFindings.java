import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates findings.json against report-schema.json.
 * Usage: java ValidateFindings.java [--schema FILE] <path-to-findings.json>
 *
 * <p>A Java port of the vendored {@code validate-findings.cjs} (see UPSTREAM.md): a dependency-free interpreter for the JSON Schema keywords
 * used by report-schema.json, plus the finding-specific checks, with the same rules, limits, messages, output and exit codes. Needs JDK 25
 * (the other source files of this folder, {@code Json.java}, are compiled with it).
 */
public final class ValidateFindings {

    private static final Set<String> SUPPORTED_TYPES = Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of("$comment", "additionalProperties", "const", "description", "enum", "items",
            "minimum", "minItems", "minLength", "oneOf", "pattern", "properties", "required", "type", "uniqueItems", "visibleContent");
    private static final Map<String, Integer> SEVERITY_RANK = Map.of("informational", 0, "low", 1, "medium", 2, "high", 3, "critical", 4);

    static final int INPUT_BYTES = 5 * 1024 * 1024;
    static final int NESTING_DEPTH = 64;
    static final int ARRAY_ITEMS = 1000;
    static final int CANONICAL_KEY_BYTES = 1024 * 1024;
    static final int UNIQUE_SET_BYTES = 5 * 1024 * 1024;
    static final int VALIDATION_ERRORS = 100;

    private static final Pattern WINDOWS_RESERVED_COMPONENT = Pattern.compile(
            "^(?:con|prn|aux|nul|clock\\$|conin\\$|conout\\$|com[1-9\\u00b9\\u00b2\\u00b3]|lpt[1-9\\u00b9\\u00b2\\u00b3])(?:\\.|\\z)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ValidateFindings() {
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
    // Canonical keys (uniqueness)
    // ---------------------------------------------------------------------------------------------------------------

    private record Canonical(String key, int bytes) {
    }

    private static Canonical canonicalKey(Object value) {
        StringBuilder chunks = new StringBuilder();
        int[] bytes = {0};
        encode(value, chunks, bytes);
        return new Canonical(chunks.toString(), bytes[0]);
    }

    private static void append(StringBuilder chunks, int[] bytes, String chunk) {
        bytes[0] += Json.utf8Length(chunk);
        if (bytes[0] > CANONICAL_KEY_BYTES) {
            throw new IllegalStateException("canonical key exceeds " + CANONICAL_KEY_BYTES + " byte limit");
        }
        chunks.append(chunk);
    }

    private static void encode(Object item, StringBuilder chunks, int[] bytes) {
        String type = Json.typeOf(item);
        switch (type) {
            case "null" -> append(chunks, bytes, "null");
            case "string" -> append(chunks, bytes, "string:" + Json.stringify((String) item));
            case "number" -> append(chunks, bytes, "number:" + Json.numberToString((Double) item));
            case "boolean" -> append(chunks, bytes, "boolean:" + (((Boolean) item) ? "true" : "false"));
            case "array" -> {
                append(chunks, bytes, "array:[");
                List<Object> list = Json.asArray(item);
                for (int k = 0; k < list.size(); k++) {
                    if (k > 0) {
                        append(chunks, bytes, ",");
                    }
                    encode(list.get(k), chunks, bytes);
                }
                append(chunks, bytes, "]");
            }
            case "object" -> {
                append(chunks, bytes, "object:{");
                Map<String, Object> map = Json.asObject(item);
                List<String> keys = new ArrayList<>(Json.keys(map));
                keys.sort(String::compareTo);
                for (int k = 0; k < keys.size(); k++) {
                    if (k > 0) {
                        append(chunks, bytes, ",");
                    }
                    append(chunks, bytes, Json.stringify(keys.get(k)));
                    append(chunks, bytes, ":");
                    encode(map.get(keys.get(k)), chunks, bytes);
                }
                append(chunks, bytes, "}");
            }
            default -> append(chunks, bytes, type + ":" + item);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Limits on the parsed data
    // ---------------------------------------------------------------------------------------------------------------

    private record Frame(Object value, String location, int depth) {
    }

    /** The first limit the data breaks, as a list of one message, or an empty list. */
    static List<String> collectDataLimitErrors(Object value, String location) {
        java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();
        stack.push(new Frame(value, location, Json.typeofObject(value) && value != Json.NULL ? 1 : 0));
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        while (!stack.isEmpty()) {
            Frame current = stack.pop();
            if (!(current.value instanceof Map || current.value instanceof List)) {
                continue;
            }
            if (current.depth > NESTING_DEPTH) {
                return List.of(Json.escapeUnsafe(current.location + ": exceeds " + NESTING_DEPTH + " level nesting depth limit"));
            }
            if (seen.put(current.value, Boolean.TRUE) != null) {
                return List.of(Json.escapeUnsafe(current.location + ": input must not contain repeated or cyclic object references"));
            }
            if (current.value instanceof List<?> list) {
                if (list.size() > ARRAY_ITEMS) {
                    return List.of(Json.escapeUnsafe(current.location + ": exceeds " + ARRAY_ITEMS + " item array limit"));
                }
                for (int index = list.size() - 1; index >= 0; index--) {
                    Object child = list.get(index);
                    if (child instanceof Map || child instanceof List) {
                        stack.push(new Frame(child, current.location + "[" + index + "]", current.depth + 1));
                    }
                }
            } else {
                Map<String, Object> map = Json.asObject(current.value);
                List<String> keys = Json.keys(map);
                for (int index = keys.size() - 1; index >= 0; index--) {
                    String key = keys.get(index);
                    Object child = map.get(key);
                    if (child instanceof Map || child instanceof List) {
                        stack.push(new Frame(child, Json.propertyPath(current.location, key), current.depth + 1));
                    }
                }
            }
        }
        return List.of();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The schema itself
    // ---------------------------------------------------------------------------------------------------------------

    static Errors collectSchemaErrors(Object schema, String location) {
        Errors errors = new Errors();
        checkSchema(schema, location, errors);
        return errors;
    }

    private static void checkSchema(Object node, String p, Errors errors) {
        if (!Json.isObject(node)) {
            errors.push(p + ": schema must be an object");
            return;
        }
        Map<String, Object> map = Json.asObject(node);
        for (String key : Json.keys(map)) {
            if (!SUPPORTED_KEYWORDS.contains(key)) {
                errors.push(p + ": unsupported schema keyword " + Json.safeQuote(key));
            }
        }
        if (map.containsKey("$comment") && !(map.get("$comment") instanceof String)) {
            errors.push(p + ".$comment: expected string");
        }
        if (map.containsKey("description") && !(map.get("description") instanceof String)) {
            errors.push(p + ".description: expected string");
        }
        if (map.containsKey("type") && !(map.get("type") instanceof String type && SUPPORTED_TYPES.contains(type))) {
            errors.push(p + ".type: unsupported type " + Json.safeQuote(map.get("type")));
        }
        if (map.containsKey("properties")) {
            if (!Json.isObject(map.get("properties"))) {
                errors.push(p + ".properties: expected object");
            } else {
                Map<String, Object> properties = Json.asObject(map.get("properties"));
                for (String key : Json.keys(properties)) {
                    checkSchema(properties.get(key), Json.propertyPath(p + ".properties", key), errors);
                }
            }
        }
        if (map.containsKey("required")) {
            Object required = map.get("required");
            if (!(required instanceof List<?> list) || list.stream().anyMatch(key -> !(key instanceof String))) {
                errors.push(p + ".required: expected an array of strings");
            } else if (new HashSet<>(list).size() != list.size()) {
                errors.push(p + ".required: entries must be unique");
            }
        }
        if (map.containsKey("additionalProperties") && !(map.get("additionalProperties") instanceof Boolean)) {
            errors.push(p + ".additionalProperties: only boolean values are supported");
        }
        if (map.containsKey("enum")) {
            Object values = map.get("enum");
            if (!(values instanceof List<?> list) || list.isEmpty()) {
                errors.push(p + ".enum: expected a non-empty array");
            } else {
                Set<String> seen = new HashSet<>();
                for (Object value : list) {
                    String key;
                    try {
                        key = canonicalKey(value).key;
                    } catch (IllegalStateException e) {
                        errors.push(p + ".enum: " + e.getMessage());
                        break;
                    }
                    if (!seen.add(key)) {
                        errors.push(p + ".enum: entries must be unique");
                        break;
                    }
                }
            }
        }
        if (map.containsKey("items")) {
            checkSchema(map.get("items"), p + ".items", errors);
        }
        for (String keyword : List.of("minItems", "minLength")) {
            if (map.containsKey(keyword) && !(Json.isInteger(map.get(keyword)) && (Double) map.get(keyword) >= 0)) {
                errors.push(p + "." + keyword + ": expected a non-negative integer");
            }
        }
        if (map.containsKey("minimum") && !Json.isFiniteNumber(map.get("minimum"))) {
            errors.push(p + ".minimum: expected a finite number");
        }
        if (map.containsKey("pattern")) {
            if (!(map.get("pattern") instanceof String pattern)) {
                errors.push(p + ".pattern: expected string");
            } else if (!Json.isRegex(pattern)) {
                errors.push(p + ".pattern: invalid regular expression");
            }
        }
        if (map.containsKey("uniqueItems") && !(map.get("uniqueItems") instanceof Boolean)) {
            errors.push(p + ".uniqueItems: expected boolean");
        }
        if (map.containsKey("visibleContent")) {
            if (!(map.get("visibleContent") instanceof Boolean flag)) {
                errors.push(p + ".visibleContent: expected boolean");
            } else if (flag && !"string".equals(map.get("type"))) {
                errors.push(p + ".visibleContent: requires type \"string\"");
            }
        }
        if (map.containsKey("oneOf")) {
            if (!(map.get("oneOf") instanceof List<?> branches) || branches.isEmpty()) {
                errors.push(p + ".oneOf: expected a non-empty array");
            } else {
                for (int index = 0; index < branches.size(); index++) {
                    checkSchema(branches.get(index), p + ".oneOf[" + index + "]", errors);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The schema interpreter
    // ---------------------------------------------------------------------------------------------------------------

    private record Discriminator(String key, Object value) {
    }

    private static Discriminator findDiscriminator(Object schema) {
        if (!Json.hasOwn(schema, "properties") || !Json.typeofObject(Json.get(schema, "properties"))) {
            return null;
        }
        Object properties = Json.get(schema, "properties");
        if (!Json.isObject(properties)) {
            return null;
        }
        Map<String, Object> map = Json.asObject(properties);
        for (String key : Json.keys(map)) {
            Object subSchema = map.get(key);
            if (Json.truthy(subSchema) && Json.typeofObject(subSchema) && Json.hasOwn(subSchema, "const")) {
                return new Discriminator(key, Json.get(subSchema, "const"));
            }
        }
        return null;
    }

    private static void validate(Object value, Map<String, Object> schema, String p, Errors errors) {
        if (errors.size() >= VALIDATION_ERRORS) {
            return;
        }
        if (schema.containsKey("oneOf")) {
            List<Object> branches = Json.asArray(schema.get("oneOf"));
            List<Errors> results = new ArrayList<>();
            for (Object branch : branches) {
                results.add(collectUnchecked(value, Json.asObject(branch), p));
            }
            List<Integer> passing = new ArrayList<>();
            for (int index = 0; index < results.size(); index++) {
                if (results.get(index).size() == 0) {
                    passing.add(index);
                }
            }
            if (passing.size() != 1) {
                errors.push(p + ": must match exactly one schema in oneOf; matched " + passing.size());
                if (passing.isEmpty() && Json.isObject(value)) {
                    List<Integer> matching = new ArrayList<>();
                    for (int index = 0; index < branches.size(); index++) {
                        Discriminator d = findDiscriminator(branches.get(index));
                        if (d != null && Json.hasOwn(value, d.key) && Json.deepEqual(Json.get(value, d.key), d.value)) {
                            matching.add(index);
                        }
                    }
                    if (matching.size() == 1) {
                        errors.pushAll(results.get(matching.get(0)).list);
                    }
                }
            }
        }
        if (schema.containsKey("const") && !Json.deepEqual(value, schema.get("const"))) {
            errors.push(p + ": must equal " + Json.safeQuote(schema.get("const")) + ", got " + Json.safeQuote(value));
        }
        if (schema.containsKey("enum")) {
            List<Object> allowed = Json.asArray(schema.get("enum"));
            if (allowed.stream().noneMatch(candidate -> Json.deepEqual(value, candidate))) {
                StringBuilder list = new StringBuilder();
                for (int k = 0; k < allowed.size(); k++) {
                    list.append(k > 0 ? ", " : "").append(Json.safeQuote(allowed.get(k)));
                }
                errors.push(p + ": invalid value " + Json.safeQuote(value) + " (expected one of " + list + ")");
            }
        }
        if (schema.containsKey("type")) {
            String expected = (String) schema.get("type");
            String actual = Json.typeOf(value);
            if (!actual.equals(expected) && !("integer".equals(expected) && "number".equals(actual) && Json.isInteger(value))) {
                errors.push(p + ": expected " + expected + ", got " + actual);
                return;
            }
        }
        if (Json.isObject(value)) {
            Map<String, Object> object = Json.asObject(value);
            if (schema.containsKey("required")) {
                for (Object req : Json.asArray(schema.get("required"))) {
                    if (!object.containsKey((String) req)) {
                        errors.push(p + ": missing required field " + Json.safeQuote(req));
                    }
                }
            }
            for (String key : Json.keys(object)) {
                if (schema.containsKey("properties") && Json.asObject(schema.get("properties")).containsKey(key)) {
                    validate(object.get(key), Json.asObject(Json.asObject(schema.get("properties")).get(key)), Json.propertyPath(p, key), errors);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    errors.push(p + ": unexpected field " + Json.safeQuote(key));
                }
            }
        }
        if (Json.isArray(value)) {
            List<Object> list = Json.asArray(value);
            if (schema.containsKey("minItems") && list.size() < (Double) schema.get("minItems")) {
                errors.push(p + ": must have at least " + Json.numberToString((Double) schema.get("minItems")) + " item(s), got " + list.size());
            }
            if (Boolean.TRUE.equals(schema.get("uniqueItems"))) {
                Set<String> seen = new HashSet<>();
                int setBytes = 0;
                for (int i = 0; i < list.size(); i++) {
                    Canonical canonical;
                    try {
                        canonical = canonicalKey(list.get(i));
                    } catch (IllegalStateException e) {
                        errors.push(p + "[" + i + "]: " + e.getMessage());
                        break;
                    }
                    if (seen.contains(canonical.key)) {
                        errors.push(p + ": items must be unique; duplicate at index " + i);
                        continue;
                    }
                    setBytes += canonical.bytes;
                    if (setBytes > UNIQUE_SET_BYTES) {
                        errors.push(p + ": canonical uniqueness set exceeds " + UNIQUE_SET_BYTES + " byte limit");
                        break;
                    }
                    seen.add(canonical.key);
                }
            }
            if (schema.containsKey("items")) {
                Map<String, Object> items = Json.asObject(schema.get("items"));
                for (int index = 0; index < list.size(); index++) {
                    validate(list.get(index), items, p + "[" + index + "]", errors);
                }
            }
        }
        if (value instanceof String text) {
            if (schema.containsKey("minLength") && Json.codePointLength(text) < (Double) schema.get("minLength")) {
                errors.push(p + ": must have at least " + Json.numberToString((Double) schema.get("minLength")) + " character(s)");
            }
            if (Boolean.TRUE.equals(schema.get("visibleContent"))) {
                if (!Json.hasValidUnicodeScalarValues(text)) {
                    errors.push(p + ": must contain only valid Unicode scalar values");
                } else if (!Json.hasVisibleCharacter(text)) {
                    errors.push(p + ": must contain a visible character");
                }
            }
            if (schema.containsKey("pattern") && !Json.compile((String) schema.get("pattern"), 0).matcher(text).find()) {
                errors.push(p + ": must match pattern " + Json.stringify((String) schema.get("pattern")));
            }
        }
        if (value instanceof Double number && schema.containsKey("minimum") && number < (Double) schema.get("minimum")) {
            errors.push(p + ": must be at least " + Json.numberToString((Double) schema.get("minimum")) + ", got " + Json.numberToString(number));
        }
    }

    private static Errors collectUnchecked(Object value, Map<String, Object> schema, String p) {
        Errors errors = new Errors();
        validate(value, schema, p, errors);
        return errors;
    }

    /** {@code collect} of the originals: the data limits first, then the schema. */
    static List<String> collect(Object value, Map<String, Object> schema, String p) {
        List<String> limitErrors = collectDataLimitErrors(value, p);
        if (!limitErrors.isEmpty()) {
            return limitErrors;
        }
        return collectUnchecked(value, schema, p).list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Finding-specific checks
    // ---------------------------------------------------------------------------------------------------------------

    static boolean isSafeRelativeSourcePath(Object value) {
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

    private static boolean visibleString(Object value) {
        return value instanceof String text && Json.hasVisibleProse(text);
    }

    private static boolean kindIs(Object entry, String expected) {
        return Json.truthy(entry) && expected.equals(Json.get(entry, "kind"));
    }

    private static Object kindOf(Object entry) {
        return Json.truthy(entry) ? Json.get(entry, "kind") : entry;
    }

    static Errors collectFindingSemanticErrors(Object findings) {
        Errors errors = new Errors();
        if (!Json.isArray(findings)) {
            return errors;
        }
        Map<String, Integer> fingerprints = new HashMap<>();
        String previousFingerprint = null;
        List<Object> list = Json.asArray(findings);
        for (int index = 0; index < list.size(); index++) {
            if (errors.size() >= VALIDATION_ERRORS) {
                continue;
            }
            Object finding = list.get(index);
            if (!Json.isObject(finding)) {
                continue;
            }
            String base = "$[" + index + "]";

            if (Json.hasOwn(finding, "fingerprint") && Json.get(finding, "fingerprint") instanceof String fingerprint) {
                if (fingerprints.containsKey(fingerprint)) {
                    errors.push(base + ".fingerprint: duplicate of $[" + fingerprints.get(fingerprint) + "].fingerprint");
                } else {
                    fingerprints.put(fingerprint, index);
                }
                if (previousFingerprint != null && previousFingerprint.compareTo(fingerprint) > 0) {
                    errors.push(base + ".fingerprint: findings must be sorted lexicographically");
                }
                previousFingerprint = fingerprint;
            }

            for (String field : List.of("trace", "evidence")) {
                if (!Json.hasOwn(finding, field) || !Json.isArray(Json.get(finding, field))) {
                    continue;
                }
                List<Object> entries = Json.asArray(Json.get(finding, field));
                for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                    Object entry = entries.get(entryIndex);
                    if (!Json.isObject(entry)) {
                        continue;
                    }
                    if (Json.hasOwn(entry, "line") && !(Json.isInteger(Json.get(entry, "line")) && (Double) Json.get(entry, "line") >= 1)) {
                        errors.push(base + "." + field + "[" + entryIndex + "].line: must be a positive integer");
                    }
                    if (Json.hasOwn(entry, "file") && !isSafeRelativeSourcePath(Json.get(entry, "file"))) {
                        errors.push(base + "." + field + "[" + entryIndex + "].file: must be a safe repository-relative source path");
                    }
                }
            }
            Object remediation = Json.get(finding, "remediation");
            if (Json.truthy(remediation) && Json.isArray(Json.get(remediation, "code_changes"))) {
                List<Object> changes = Json.asArray(Json.get(remediation, "code_changes"));
                for (int changeIndex = 0; changeIndex < changes.size(); changeIndex++) {
                    Object change = changes.get(changeIndex);
                    if (Json.truthy(change) && Json.hasOwn(change, "file_name") && !isSafeRelativeSourcePath(Json.get(change, "file_name"))) {
                        errors.push(base + ".remediation.code_changes[" + changeIndex
                                + "].file_name: must be a safe repository-relative source path");
                    }
                }
            }

            Object trace = Json.get(finding, "trace");
            if (Json.isArray(trace) && Json.asArray(trace).size() == 1) {
                Object kind = kindOf(Json.asArray(trace).get(0));
                if (!"entrypoint".equals(kind) && !"sink".equals(kind)) {
                    errors.push(base + ".trace[0].kind: a one-line trace must be \"entrypoint\" or \"sink\"");
                }
            } else if (Json.isArray(trace) && Json.asArray(trace).size() > 1) {
                List<Object> steps = Json.asArray(trace);
                int last = steps.size() - 1;
                if (Json.truthy(steps.get(0)) && !"entrypoint".equals(Json.get(steps.get(0), "kind"))) {
                    errors.push(base + ".trace[0].kind: must be \"entrypoint\", got " + Json.safeQuote(Json.get(steps.get(0), "kind")));
                }
                if (Json.truthy(steps.get(last)) && !"sink".equals(Json.get(steps.get(last), "kind"))) {
                    errors.push(base + ".trace[" + last + "].kind: must be \"sink\", got " + Json.safeQuote(Json.get(steps.get(last), "kind")));
                }
                for (int traceIndex = 1; traceIndex < last; traceIndex++) {
                    if (Json.truthy(steps.get(traceIndex)) && !"propagation".equals(Json.get(steps.get(traceIndex), "kind"))) {
                        errors.push(base + ".trace[" + traceIndex + "].kind: must be \"propagation\", got "
                                + Json.safeQuote(Json.get(steps.get(traceIndex), "kind")));
                    }
                }
            }

            Object verdict = Json.get(finding, "verdict");
            if ("confirmed".equals(verdict)) {
                for (String forbidden : List.of("claimed_root_cause", "blockers", "validation_plan", "reason")) {
                    if (Json.hasOwn(finding, forbidden)) {
                        errors.push(base + ": confirmed finding must not contain " + Json.safeQuote(forbidden));
                    }
                }
                Object execution = Json.get(finding, "execution");
                if (!Json.truthy(execution) || !Json.typeofObject(execution) || !visibleString(Json.get(execution, "observed_result"))) {
                    errors.push(base + ": confirmed finding requires a visible execution observed_result");
                }
                Object remediationValue = Json.get(finding, "remediation");
                if (!Json.truthy(remediationValue) || !Json.typeofObject(remediationValue) || !visibleString(Json.get(remediationValue, "strategy"))) {
                    errors.push(base + ": confirmed finding requires visible remediation");
                }
                Object severity = Json.get(finding, "severity");
                Object overall = Json.truthy(severity) ? Json.get(severity, "overall_severity") : severity;
                Object impactObject = Json.truthy(severity) ? Json.get(severity, "impact") : severity;
                Object impact = Json.truthy(impactObject) ? Json.get(impactObject, "score") : impactObject;
                if (overall instanceof String o && impact instanceof String i && SEVERITY_RANK.containsKey(o) && SEVERITY_RANK.containsKey(i)
                        && SEVERITY_RANK.get(o) > SEVERITY_RANK.get(i)) {
                    errors.push(base + ".severity.overall_severity: cannot exceed demonstrated impact " + Json.safeQuote(impact));
                }
            } else if ("needs_validation".equals(verdict)) {
                if (Json.hasOwn(finding, "severity")) {
                    errors.push(base + ": needs_validation finding must not contain \"severity\"");
                }
                for (String forbidden : List.of("execution", "remediation", "reason", "root_cause")) {
                    if (Json.hasOwn(finding, forbidden)) {
                        errors.push(base + ": needs_validation finding must not contain " + Json.safeQuote(forbidden));
                    }
                }
                Object plan = Json.get(finding, "validation_plan");
                boolean hasLocalPlan = Json.truthy(plan) && visibleString(Json.get(plan, "local"));
                boolean hasDeploymentPlan = Json.truthy(plan) && visibleString(Json.get(plan, "deployment"));
                if (!hasLocalPlan && !hasDeploymentPlan) {
                    errors.push(base + ".validation_plan: requires at least one visible local or deployment plan");
                }
            } else if ("rejected".equals(verdict)) {
                for (String forbidden : List.of("severity", "execution", "remediation", "blockers", "validation_plan", "root_cause")) {
                    if (Json.hasOwn(finding, forbidden)) {
                        errors.push(base + ": rejected finding must not contain " + Json.safeQuote(forbidden));
                    }
                }
            }
        }
        return errors;
    }

    static List<String> validateDocument(Object findings, Object schema) {
        Errors schemaErrors = collectSchemaErrors(schema, "schema");
        if (schemaErrors.size() > 0) {
            return schemaErrors.list;
        }
        List<String> limitErrors = collectDataLimitErrors(findings, "$");
        if (!limitErrors.isEmpty()) {
            return limitErrors;
        }
        Errors errors = collectUnchecked(findings, Json.asObject(schema), "$");
        if (errors.size() < VALIDATION_ERRORS) {
            errors.pushAll(collectFindingSemanticErrors(findings).list);
        }
        return errors.list;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The program
    // ---------------------------------------------------------------------------------------------------------------

    /** An open array or object while the text limits are checked. */
    private static final class Open {
        final boolean array;
        boolean expectsItem;
        int items;

        Open(boolean array) {
            this.array = array;
            this.expectsItem = array;
        }
    }

    /** The limits that are checked before the text is parsed: nesting depth and the number of items of an array. */
    static void enforceJsonTextLimits(String contents) {
        java.util.ArrayDeque<Open> containers = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
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
            if (character == '"') {
                markArrayItem(containers);
                inString = true;
            } else if (character == '[' || character == '{') {
                markArrayItem(containers);
                if (containers.size() >= NESTING_DEPTH) {
                    throw new JsonStructureError("input exceeds " + NESTING_DEPTH + " level nesting depth limit");
                }
                containers.push(new Open(character == '['));
            } else if (character == ']' || character == '}') {
                containers.poll();
            } else if (character == ',') {
                Open top = containers.peek();
                if (top != null && top.array) {
                    top.expectsItem = true;
                }
            } else if (!Json.isJsSpace(character)) {
                markArrayItem(containers);
            }
        }
    }

    private static void markArrayItem(java.util.ArrayDeque<Open> containers) {
        Open top = containers.peek();
        if (top == null || !top.array || !top.expectsItem) {
            return;
        }
        top.expectsItem = false;
        top.items++;
        if (top.items > ARRAY_ITEMS) {
            throw new JsonStructureError("input exceeds " + ARRAY_ITEMS + " item array limit");
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path schemaFile = null;
        String file = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--schema") && i + 1 < args.length) {
                schemaFile = Path.of(args[++i]);
            } else if (file == null) {
                file = args[i];
            } else {
                file = null;
                break;
            }
        }
        if (file == null) {
            err.println("Usage: java ValidateFindings.java [--schema FILE] <path-to-findings.json>");
            return 1;
        }
        if (schemaFile == null) {
            Path folder = Json.scriptFolder();
            schemaFile = (folder == null ? Path.of("") : folder).resolve("report-schema.json");
        }

        Object schema;
        try {
            schema = Json.parse(Files.readString(schemaFile));
            Errors schemaErrors = collectSchemaErrors(schema, "schema");
            if (schemaErrors.size() > 0) {
                throw new IllegalStateException("unsupported or invalid report schema:\n" + String.join("\n", schemaErrors.list));
            }
        } catch (Exception e) {
            err.println("Failed to load report-schema.json: " + e.getMessage());
            return 1;
        }

        String contents;
        try {
            contents = Json.readWithinLimit(Path.of(file), INPUT_BYTES);
        } catch (Json.SafeInputError e) {
            err.println("Failed to read findings JSON: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            err.println("Failed to read findings JSON: input could not be opened or read safely");
            return 1;
        }

        try {
            enforceJsonTextLimits(contents);
        } catch (JsonStructureError e) {
            err.println("Failed to parse findings JSON: " + e.getMessage());
            return 1;
        }

        Object findings;
        try {
            findings = Json.parse(contents);
        } catch (Json.SyntaxError e) {
            err.println("Failed to parse findings JSON: invalid JSON syntax");
            return 1;
        }

        List<String> errors;
        try {
            errors = validateDocument(findings, schema);
        } catch (RuntimeException e) {
            err.println("Failed to validate findings JSON: unexpected validation error");
            return 1;
        }
        for (String message : errors) {
            err.println("ERROR: " + Json.escapeUnsafe(message));
        }
        if (!errors.isEmpty()) {
            String cap = errors.size() == VALIDATION_ERRORS ? "; output capped at " + VALIDATION_ERRORS : "";
            err.println("FAIL: " + errors.size() + " validation error(s)" + cap);
            return 1;
        }
        out.println("PASS: " + (Json.isArray(findings) ? Json.asArray(findings).size() : "undefined") + " findings valid");
        return 0;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
