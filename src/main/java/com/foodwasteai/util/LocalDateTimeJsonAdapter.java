package com.foodwasteai.util;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.*;

/** Explicit offsets on the wire, preserving the existing two storage contracts. */
public class LocalDateTimeJsonAdapter extends TypeAdapter<LocalDateTime> {
    protected ZoneId zone() { return ZoneOffset.UTC; }
    @Override public void write(JsonWriter out, LocalDateTime value) throws IOException {
        if (value == null) { out.nullValue(); return; }
        out.value(value.atZone(zone()).toOffsetDateTime().toString());
    }
    @Override public LocalDateTime read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
        String value = in.nextString().replace(' ', 'T');
        if (value.length() == 10) return LocalDate.parse(value).atStartOfDay();
        if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return OffsetDateTime.parse(value).atZoneSameInstant(zone()).toLocalDateTime();
        }
        return LocalDateTime.parse(value);
    }
    public static final class Scheduled extends LocalDateTimeJsonAdapter {
        @Override protected ZoneId zone() { return AppTime.APP_ZONE; }
    }
}
