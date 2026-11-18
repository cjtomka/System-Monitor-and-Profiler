package com.example.monitor.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Turns one NDJSON line into a Message. Stateless and thread-safe. */
public final class MessageDecoder {
    public static final int SUPPORTED_VERSION = 1;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // forward compatibility
            .build();

    private MessageDecoder() {}

    public static Message decode(String line) throws DecodeException {
        JsonNode root;
        try {
            root = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw new DecodeException("invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new DecodeException("expected a JSON object", null);
        }

        JsonNode v = root.get("v");
        if (v != null && !(v.isInt() && v.asInt() == SUPPORTED_VERSION)) {
            throw new UnsupportedVersionException(v.asText());
        }

        String type = root.path("type").asText("");
        try {
            return switch (type) {
                case "hello"    -> MAPPER.treeToValue(root, Message.Hello.class);
                case "snapshot" -> MAPPER.treeToValue(root, Message.Snapshot.class);
                case "history"  -> MAPPER.treeToValue(root, Message.History.class);
                case "error"    -> MAPPER.treeToValue(root, Message.ErrorMessage.class);
                case "pong"     -> MAPPER.treeToValue(root, Message.Pong.class);
                default         -> new Message.Unknown(type);
            };
        } catch (JsonProcessingException e) {
            throw new DecodeException("bad " + type + " message: " + e.getOriginalMessage(), e);
        }
    }
}