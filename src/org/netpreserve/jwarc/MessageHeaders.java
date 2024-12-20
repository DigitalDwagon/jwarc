/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2018 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;

public class MessageHeaders {
    private static Pattern COMMA_SEPARATOR = Pattern.compile("[ \t]*,[ \t]*");
    private Map<String,List<String>> map;

    public static MessageHeaders of(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("an even number keysAndValues must be provided");
        }
        Map<String,List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.computeIfAbsent(keysAndValues[i], k -> new ArrayList<>()).add(keysAndValues[i + 1]);
        }
        return new MessageHeaders(map);
    }

    MessageHeaders(Map<String, List<String>> map) {
        map.replaceAll((name, values) -> Collections.unmodifiableList(values));
        this.map = Collections.unmodifiableMap(map);
    }

    /**
     * Returns the value of a single-valued header field. Throws an exception if there are more than one.
     */
    public Optional<String> sole(String name) {
        List<String> values = all(name);
        if (values.size() > 1) {
            throw new IllegalArgumentException("record has " + values.size() + " " + name + " headers");
        }
        return values.stream().findFirst();
    }

    /**
     * Returns the first value of a header field.
     */
    public Optional<String> first(String name) {
        return all(name).stream().findFirst();
    }

    /**
     * Returns all the values of a header field.
     */
    public List<String> all(String name) {
        return map.getOrDefault(name, emptyList());
    }

    /**
     * Returns a map of header fields to their values.
     */
    public Map<String,List<String>> map() {
        return map;
    }

    /**
     * Returns true when the given header value is present.
     *
     * Fields are interpreted as a comma-separated list and the value is compared case-insensitively.
     */
    public boolean contains(String name, String value) {
        for (String rawValue : all(name)) {
            for (String splitValue : COMMA_SEPARATOR.split(rawValue)) {
                if (splitValue.equalsIgnoreCase(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return map.toString();
    }

    /**
     * Parses application/warc-fields.
     */
    public static MessageHeaders parse(ReadableByteChannel channel) throws IOException {
        WarcParser parser = WarcParser.newWarcFieldsParser();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (!parser.isFinished()) {
            int n = channel.read(buffer);
            if (n < 0) {
                parser.parse(ByteBuffer.wrap("\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
                break;
            }
            buffer.flip();
            parser.parse(buffer);
            if (parser.isError()) throw new ParsingException("invalid WARC fields");
            buffer.compact();
        }
        return parser.headers();
    }

    private static final boolean[] ILLEGAL = initIllegalLookup();
    private static boolean[] initIllegalLookup() {
        boolean[] illegal = new boolean[256];
        String separators = "()<>@,;:\\\"/[]?={} \t";
        for (int i = 0; i < separators.length(); i++) {
            illegal[separators.charAt(i)] = true;
        }
        for (int i = 0; i < 32; i++) { // control characters
            illegal[i] = true;
        }
        return illegal;
    }

    static String format(Map<String, List<String>> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            String name = entry.getKey();
            for (String value : entry.getValue()) {
                out.append(name).append(": ").append(value).append("\r\n");
            }
        }
        return out.toString();
    }

    public void appendTo(Appendable appendable) throws IOException {
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            String name = entry.getKey();
            for (String value : entry.getValue()) {
                appendable.append(name).append(": ").append(value).append("\r\n");
            }
        }
    }
}
