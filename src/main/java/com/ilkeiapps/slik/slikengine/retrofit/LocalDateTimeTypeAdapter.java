package com.ilkeiapps.slik.slikengine.retrofit;

import com.google.gson.*;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class LocalDateTimeTypeAdapter implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {

    // Format lama: "2025-12-09 01:02:50"
    private static final DateTimeFormatter LEGACY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public JsonElement serialize(LocalDateTime localDateTime, Type srcType, JsonSerializationContext context) {
        if (localDateTime == null) {
            return JsonNull.INSTANCE;
        }
        // Kalau mau, tetap kirim dengan format lama
        return new JsonPrimitive(LEGACY_FORMATTER.format(localDateTime));
        // Atau kalau mau ISO, bisa pakai:
        // return new JsonPrimitive(localDateTime.toString());
    }

    @Override
    public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {

        if (json == null || json.isJsonNull()) {
            return null;
        }

        String str = json.getAsString();
        if (str == null || str.isBlank()) {
            return null;
        }

        // 1) Coba ISO: "2025-12-09T01:17:21.929525"
        try {
            return LocalDateTime.parse(str); // ISO_LOCAL_DATE_TIME
        } catch (DateTimeParseException ignored) {
            // lanjut ke format lama
        }

        // 2) Coba format lama: "2025-12-09 01:02:50"
        try {
            return LocalDateTime.parse(str, LEGACY_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new JsonParseException("Gagal parse LocalDateTime: '" + str + "'", e);
        }
    }
}
