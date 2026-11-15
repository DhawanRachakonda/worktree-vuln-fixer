package dev.subscriber.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * What the launcher script writes to {@code exit.json} when the agent ends: the exit code, the end time, and the repository's commit
 * at that moment (null when it could not be read).
 */
public record ExitRecord(int exitCode, Instant endedAt, String headEnd) {

    public static final String FILE_NAME = "exit.json";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // A number, boolean or other scalar is not accepted where text is expected (for example a status of 5).
            .withCoercionConfig(LogicalType.Textual, cfg -> cfg
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    /**
     * Reads the exit record of a session folder.
     *
     * @return empty when there is no record yet, or it is incomplete or unreadable (the launcher writes it atomically, so a
     *         partial file means something else is wrong, and the caller treats it as no record)
     */
    public static Optional<ExitRecord> read(Path sessionFolder) {
        Path file = sessionFolder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file);
            ExitRecord record = MAPPER.readValue(text.startsWith("﻿") ? text.substring(1) : text, ExitRecord.class);
            return record.endedAt() == null ? Optional.empty() : Optional.of(record);
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }
}
