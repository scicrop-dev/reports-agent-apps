package com.infinitestack.reports.blocks.cropcal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.erdtman.jcs.JsonCanonicalizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The request JSON is-crop-calendar reads from the lake staging, and its identity.
 *
 * <p>{@code request_sha} = sha256 (lower-case hex) of the UTF-8 bytes of the RFC 8785 canonical JSON of
 * the request without {@code requestId} and {@code requestSha}: exactly {@code aoi}, {@code from},
 * {@code to} and, when there are any, {@code params}. The script computes the same and refuses a file
 * whose {@code requestSha} does not match, so both sides find the same runs in gold.
 *
 * @param json        the file content: the canonical request plus requestId and requestSha
 * @param fileName    {@code reports-cropcal-<sha12>.json}
 * @param requestSha  the identity of the request
 */
public record CropCalendarRequest(byte[] json, String fileName, String requestSha) {

    static final String FILE_PREFIX = "reports-cropcal-";
    static final Map<String, Object> CRS_4674 = Map.of("type", "name", "properties", Map.of("name", "EPSG:4674"));

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param geometry the field, GeoJSON in EPSG:4674 (report-area.geometry); a "crs" member already in it is kept
     * @param params   calendar parameters by the script's names (relative_orbit...), empty for none
     */
    public static CropCalendarRequest of(Map<String, Object> geometry, LocalDate from, LocalDate to, Map<String, Object> params) {
        Map<String, Object> aoi = new LinkedHashMap<>(geometry);
        aoi.putIfAbsent("crs", CRS_4674);
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("aoi", aoi);
        hashed.put("from", from.toString());
        hashed.put("to", to.toString());
        if (params != null && !params.isEmpty()) hashed.put("params", params);

        String sha = hex(sha256(canonical(hashed)));
        Map<String, Object> file = new LinkedHashMap<>(hashed);
        file.put("requestId", FILE_PREFIX + sha.substring(0, 12));
        file.put("requestSha", sha);
        return new CropCalendarRequest(canonical(file), FILE_PREFIX + sha.substring(0, 12) + ".json", sha);
    }

    /** RFC 8785 bytes of a JSON-like value (Maps, Lists, Strings, Numbers, Booleans, null). */
    static byte[] canonical(Object value) {
        try {
            return new JsonCanonicalizer(JSON.writeValueAsString(value)).getEncodedUTF8();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the request cannot be written as JSON: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("the request cannot be canonicalized (RFC 8785): " + e.getMessage(), e);
        }
    }

    static String requestSha(String json) {
        try {
            Map<?, ?> parsed = JSON.readValue(json, Map.class);
            Map<Object, Object> hashed = new LinkedHashMap<>(parsed);
            hashed.remove("requestId");
            hashed.remove("requestSha");
            return hex(sha256(canonical(hashed)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
    }

    static String text(byte[] json) {
        return new String(json, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
