package dev.subscriber.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * What the agent wrote to {@code .appmod/result.json} in the repository when it finished. Only {@code status} matters here; the other
 * fields of the report are ignored. A report older than the session's start belongs to an earlier session and does not count.
 */
public final class AgentReport {

    /** The relative location the agent is told to write to. */
    public static final String LOCATION = ".appmod/result.json";

    /** Whether there is a usable report for this session. */
    public enum Kind {
        /** Written during this session and readable. */
        FRESH,
        /** There is no report. */
        MISSING,
        /** There is one, but it is older than the session's start. */
        STALE,
        /** There is a fresh one, but it cannot be read or has no status. */
        UNREADABLE
    }

    /** The outcome of looking for the report: its kind and, for a fresh one, its status. */
    public record Read(Kind kind, String status) {
    }

    private record ResultFile(String status) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // A number, boolean or other scalar is not accepted where text is expected (for example a status of 5).
            .withCoercionConfig(LogicalType.Textual, cfg -> cfg
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private AgentReport() {
    }

    public static Read read(Path repository, Instant sessionStartedAt) {
        Path file = repository.resolve(LOCATION);
        if (!Files.isRegularFile(file)) {
            return new Read(Kind.MISSING, null);
        }
        try {
            if (Files.getLastModifiedTime(file).toInstant().isBefore(sessionStartedAt)) {
                return new Read(Kind.STALE, null);
            }
            String text = Files.readString(file);
            ResultFile parsed = MAPPER.readValue(text.startsWith("﻿") ? text.substring(1) : text, ResultFile.class);
            return parsed == null || parsed.status() == null || parsed.status().isBlank()
                    ? new Read(Kind.UNREADABLE, null)
                    : new Read(Kind.FRESH, parsed.status().strip());
        } catch (IOException | RuntimeException _) {
            return new Read(Kind.UNREADABLE, null);
        }
    }
}
