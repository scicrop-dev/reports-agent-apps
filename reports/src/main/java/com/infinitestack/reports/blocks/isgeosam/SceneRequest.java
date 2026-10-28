package com.infinitestack.reports.blocks.isgeosam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The workflow JSON is-geo-sam takes as its only input (its PREPARAR_IMAGEM.md): a list of block
 * executions with {@code sathub-latest-image} and, when known, {@code car-property-geometry} and
 * {@code car-property}, built from this Block's inputs.
 *
 * <p>The JSON is deterministic: the same image and area give the same bytes, the same file name and
 * so the same scene in is-geo-sam, which identifies a scene by the sha256 of the file name followed
 * by its content ({@code scene._sha256}). That is what lets a report find a segmentation done
 * before, even by another report, without running the script again. Nothing that changes per
 * execution (execution id, dates of now) goes in it.
 */
public record SceneRequest(byte[] json, String fileName, String sceneSha256) {

    static final String FILE_PREFIX = "reports-isgeosam-";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param image    the outputs of sathub-latest-image this Block received (trueColor, imageCrs...)
     * @param geometry the area (GeoJSON), or null
     * @param codImovel the CAR property code, or null
     */
    public static SceneRequest of(Map<String, Object> image, Map<String, Object> geometry, String codImovel) {
        Map<String, Object> imageOut = new LinkedHashMap<>();
        imageOut.put("found", true);
        imageOut.put("trueColor", image.get("trueColor"));
        imageOut.put("imageCrs", image.get("imageCrs"));
        imageOut.put("imageTransform", doubles(image.get("imageTransform")));
        imageOut.put("imageBounds", doubles(image.get("imageBounds")));
        imageOut.put("widthPx", whole(image.get("widthPx")));
        imageOut.put("heightPx", whole(image.get("heightPx")));
        imageOut.put("pixelSizeM", decimal(image.get("pixelSizeM")));
        imageOut.put("scene", image.get("scene"));

        List<Map<String, Object>> executions = new ArrayList<>();
        if (codImovel != null && !codImovel.isBlank()) {
            executions.add(execution("car-property", Map.of("codImovel", codImovel.trim())));
        }
        if (geometry != null) {
            executions.add(execution("car-property-geometry", Map.of("geometry", geometry)));
        }
        executions.add(execution("sathub-latest-image", imageOut));

        byte[] json;
        try {
            json = JSON.writeValueAsBytes(executions);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the image outputs cannot be written as JSON: " + e.getMessage(), e);
        }
        String fileName = FILE_PREFIX + hex(sha256(json)).substring(0, 32) + ".json";
        return new SceneRequest(json, fileName, sceneSha256(fileName, json));
    }

    /** is-geo-sam's scene identity of a workflow JSON sent without mode or seeds: sha256(name + content). */
    static String sceneSha256(String fileName, byte[] content) {
        MessageDigest digest = digest();
        digest.update(fileName.getBytes(StandardCharsets.UTF_8));
        digest.update(content);
        return hex(digest.digest());
    }

    private static Map<String, Object> execution(String blockId, Map<String, Object> outputs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("block_id", blockId);
        out.put("outputs", outputs);
        return out;
    }

    /** The same numbers always print the same way, whatever Number type the engine handed over. */
    private static List<Double> doubles(Object value) {
        if (!(value instanceof List<?> list)) return null;
        List<Double> out = new ArrayList<>();
        for (Object item : list) out.add(decimal(item));
        return out;
    }

    private static Double decimal(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static Long whole(Object value) {
        return value instanceof Number n ? Math.round(n.doubleValue()) : null;
    }

    private static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
