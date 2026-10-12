package com.example.myGithubAction.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** JSON (de)serialization of execution messages; payloads travel as plain strings. */
@Component
public class ExecutionEventCodec {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String toJson(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event, e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            T event = mapper.readValue(json, type);
            if (event == null) {
                throw new MalformedEventException("Empty payload", null);
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Malformed " + type.getSimpleName() + ": " + json, e);
        }
    }

    /** A message that can never be processed; goes straight to the dead-letter topic. */
    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
