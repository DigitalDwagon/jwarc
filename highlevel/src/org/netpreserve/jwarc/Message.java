/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2018 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import org.netpreserve.jwarc.parser.ProtocolVersion;

/**
 * A message consisting of headers and a content block. Forms the basis of protocols and formats like HTTP and WARC.
 */
public abstract class Message {
    private final ProtocolVersion version;
    private final Headers headers;
    private final Body body;

    Message(ProtocolVersion version, Headers headers, Body body) {
        this.version = version;
        this.headers = headers;
        this.body = body;
    }

    /**
     * The named header fields of this message.
     */
    public Headers headers() {
        return headers;
    }

    /**
     * The content body of this message.
     */
    public Body body() {
        return body;
    }

    /**
     * The version of the network protocol or file format containing this message.
     */
    public ProtocolVersion version() {
        return version;
    }

    public static abstract class Builder<R extends Message, B extends Builder<R, B>> {
        public abstract R build();

        public abstract B header(String name, String value);

        public abstract B setHeader(String name, String value);
    }
}
