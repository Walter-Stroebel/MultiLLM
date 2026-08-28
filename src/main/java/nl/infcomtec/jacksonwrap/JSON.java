/*
 * Copyright (c) 2025 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.jacksonwrap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.awt.Component;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;

/**
 * Just domesticating Jackson.
 */
public class JSON {

    /**
     * "object-aware" version of ObjectMapper.
     */
    private static final ObjectMapper mapper = createMapper();
    /**
     * as mapper but one line
     */
    private static final ObjectMapper liner = createMapper2();

    private static ObjectMapper createMapper() {
        ObjectMapper mpr = new ObjectMapper();
        mpr.enable(SerializationFeature.INDENT_OUTPUT);
        mpr.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mpr.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mpr.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mpr.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        mpr.registerModule(new JavaTimeModule());
        return mpr;
    }

    private static ObjectMapper createMapper2() {
        ObjectMapper mpr = new ObjectMapper();
        mpr.disable(SerializationFeature.INDENT_OUTPUT);
        mpr.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mpr.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mpr.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mpr.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        mpr.registerModule(new JavaTimeModule());
        return mpr;
    }

    /**
     * Global, object-aware ObjectMapper.
     *
     * @return the mapper.
     */
    public static ObjectMapper getMapper() {
        return mapper;
    }

    /**
     * Global, object-aware ObjectMapper(single line).
     *
     * @return the mapper.
     */
    public static ObjectMapper getLiner() {
        return liner;
    }

    /**
     * Just adds the boilerplate for the "Cannot serialize" exception.
     *
     * @param obj To serialize;
     * @return the JSON (no newlines).
     */
    public static String writeValueAsString(Object obj) {
        try {
            return liner.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            Logger.getLogger(JSON.class.getName()).log(Level.SEVERE, null, ex);
            return ex.getMessage();
        }
    }

    /**
     * Just adds the boilerplate for the "Cannot serialize" exception.
     *
     * @param obj To serialize;
     * @return the JSON (with newlines).
     */
    public static String writeValueAsPretty(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            System.err.println("");
            Logger.getLogger(JSON.class.getName()).log(Level.SEVERE, null, ex);
            return ex.getMessage();
        }
    }

    /**
     * Internal helper to handle the "user problem" of bad JSON.
     */
    private static void handleParseException(Component parent, JsonProcessingException ex) {
        if (parent != null) {
            JOptionPane.showMessageDialog(parent,
                    ex.getMessage(),
                    "JSON Parsing Error",
                    JOptionPane.ERROR_MESSAGE);
        } else {
            // Fallback for headless/CLI/unit test contexts
            System.err.println("JSON Parsing Error: " + ex.getMessage());
        }
    }

    /**
     * UI-friendly read: shows a dialog on failure.
     *
     * @param <T> PoJo
     * @param parent If you happen to have one (Swing app).
     * @param json What to parse.
     * @param valueType PoJo.class
     * @return Null if parsing failed, else what you told it to do.
     */
    public static <T> T readValue(Component parent, String json, Class<T> valueType) {
        try {
            return mapper.readValue(json, valueType);
        } catch (JsonProcessingException ex) {
            handleParseException(parent, ex);
            return null;
        }
    }

    /**
     * CLI/Generic-friendly read: prints to stderr on failure.
     *
     * @param <T> PoJo
     * @param json What to parse.
     * @param valueType PoJo.class
     * @return Null if parsing failed, else what you told it to do.
     */
    public static <T> T readValue(String json, Class<T> valueType) {
        return readValue(null, json, valueType);
    }
}
