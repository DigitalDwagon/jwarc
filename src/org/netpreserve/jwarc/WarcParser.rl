// recompile: ragel -J WarcParser.rl -o WarcParser.java
// diagram:   ragel -Vp WarcParser.rl | dot -Tpng | feh -

package org.netpreserve.jwarc;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.charset.StandardCharsets.US_ASCII;

%%{

machine warc;

getkey (data.get(p) & 0xff);

action push         { push(data.get(p)); }
action add_major    { major = major * 10 + data.get(p) - '0'; }
action add_minor    { minor = minor * 10 + data.get(p) - '0'; }
action end_of_text  { endOfText = bufPos; }

action fold {
    if (bufPos > 0) {
        bufPos = endOfText;
        push((byte)' ');
    }
}

action handle_name  {
    name = new String(buf, 0, bufPos, US_ASCII);
    bufPos = 0;
}

action handle_value {
    String value = new String(buf, 0, endOfText, UTF_8);
    headerMap.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
    bufPos = 0;
    endOfText = 0;
}

action handle_arc_url {
    String url = new String(buf, 0, bufPos, ISO_8859_1);
    if (url.startsWith("filedesc://")) {
        setHeader("WARC-Type", "warcinfo");
        setHeader("WARC-Filename", url.substring("filedesc://".length()));
        setHeader("Content-Type", "text/plain");
    } else if (url.startsWith("dns:")) {
        setHeader("WARC-Type", "response");
        setHeader("Content-Type", "text/dns");
        setHeader("WARC-Target-URI", url);
     } else {
        setHeader("WARC-Type", "response");
        setHeader("Content-Type", "application/http;msgtype=response");
        setHeader("WARC-Target-URI", url);
    }
    bufPos = 0;
}

action handle_arc_ip {
    setHeader("WARC-IP-Address", new String(buf, 0, bufPos, US_ASCII));
    bufPos = 0;
}

action handle_arc_date {
    String arcDate = new String(buf, 0, bufPos, US_ASCII);
    // Some WARC files have been seen in the wild with truncated dates
    if (arcDate.length() < 14) {
        emitWarning("ARC date too short (" + arcDate.length() + " digits)");
        arcDate = arcDate + "00000000000000".substring(arcDate.length());
    } else if (arcDate.length() > 14) {
        emitWarning("ARC date too long (" + arcDate.length() + " digits)");
        arcDate = arcDate.substring(0, 14);
    }
    try {
        Instant instant = LocalDateTime.parse(arcDate, arcTimeFormat).toInstant(ZoneOffset.UTC);
        setHeader("WARC-Date", instant.toString());
    } catch (DateTimeParseException e) {
        emitWarning("ARC date not parsable");
    }
    bufPos = 0;
}

action handle_arc_content_type {
    String contentType = new String(buf, 0, bufPos, US_ASCII);
    // Normally the ARC content-type header is copied from the HTTP header, but as a special case "alexa/dat"
    // indicates the body is not a HTTP message.
    if (contentType.equals("alexa/dat")) {
        setHeader("Content-Type", "alexa/dat");
    }
    bufPos = 0;
}

action handle_arc_length {
    setHeader("Content-Length", new String(buf, 0, bufPos, US_ASCII));
    bufPos = 0;
}

action handle_arc_status {
    bufPos = 0;
}

action handle_arc {
    protocol = "ARC";
    major = 1;
    minor = 1;
}

CRLF = "\r\n";

version_major = digit+ $add_major;
version_minor = digit+ $add_minor;
version = "WARC/" version_major "." version_minor CRLF ;

CHAR = 0..0x7f | 0x80..0xbf | 0xc2..0xf4;
CTL = cntrl | 127;
WS = " " | "\t";
RWS = WS+;
OWS = WS*;
LWS = CRLF RWS;
WORD = (any - CTL - WS)+;
TEXT = WORD (RWS WORD)* %end_of_text;

separators = "(" | ")" | "<" | ">" | "@"
           | "," | ";" | ":" | "\\" | '"'
           | "/" | "[" | "]" | "?" | "="
           | "{" | "}" | " " | "\t";

url_byte = alpha | digit | "!" | "$" | "&" | "'" | "(" | ")"
         | "*" | "+" | "," | "-" | "." | "/" | ":" | ";"
         | "=" | "?" | "@" | "_" | "~" | "%" | 0x80..0xff;

field_name = ((ascii - CTL - separators)+) $push %handle_name;
field_value_first = OWS (TEXT OWS)? $push;
field_value_folded = LWS (TEXT OWS)? >fold $push;
field_value = field_value_first (field_value_folded)*;
named_field = field_name ":" field_value CRLF %handle_value;
named_fields = named_field* CRLF;
warc_header = version named_fields;

CRLF_lenient = "\r"* "\n";
LWS_lenient = CRLF_lenient RWS;
TEXT_lenient = ((any - '\n' - WS) (any - '\n')*)? (any - '\n' - WS - '\r') %end_of_text;

version_lenient = "WARC/" version_major "." version_minor+ CRLF_lenient ;

field_name_lenient = ((any - '\r' - '\n' - ' ' - '\t' - ':') (any - '\r' - '\n' - ':')*) $push %handle_name;
field_value_first_lenient = OWS (TEXT_lenient OWS)? $push;
field_value_folded_lenient = LWS_lenient (TEXT_lenient OWS)? >fold $push;
field_value_lenient = field_value_first_lenient (field_value_folded_lenient)*;
named_field_lenient = field_name_lenient ":" field_value_lenient CRLF_lenient %handle_value;
named_fields_lenient = named_field_lenient* CRLF_lenient;
warc_header_lenient = version_lenient named_fields_lenient;

token = (ascii - CTL - separators)+;
obs_text = 0x80..0xff;
qdtext = "\t" | " " | 0x21 | 0x23..0x5b | 0x5d..0x7e | obs_text;
quoted_pair = "\\" CHAR;
quoted_string = '"' (qdtext | quoted_pair)* '"';
parameter = token "=" (token | quoted_string );

arc_url_byte = any - "\n" - " ";
arc_url = (lower+ ":" arc_url_byte*) $push %handle_arc_url;
arc_ip = ("0" | digit{1,3} "." digit{1,3} "." digit{1,3} "." digit{1,3}) $push %handle_arc_ip;
arc_date = digit{14} $push %handle_arc_date;
arc_date_lenient = digit{8,28} $push %handle_arc_date;
arc_content_type = (token ("/" token ( OWS ";" OWS parameter )*)?)?;
arc_content_type_lenient = arc_content_type | (any - " " - "\n")* $push %handle_arc_content_type;
arc_length = digit+ $push %handle_arc_length %handle_arc;

arc_v2_status = digit{3} $push %handle_arc_status;
arc_v2_checksum = arc_url_byte+;
arc_v2_location = arc_url_byte+;
arc_v2_offset = digit+;
arc_v2_filename = arc_url_byte+;
arc_v2_fields = arc_v2_status " " arc_v2_checksum " " arc_v2_location " " arc_v2_offset " " arc_v2_filename;

arc_header = "\n"{0,3} arc_url " " arc_ip " " arc_date_lenient " " arc_content_type_lenient
             " " (arc_v2_fields " ")? arc_length "\n";

warc_fields_lenient := named_fields_lenient;
warc_fields := named_fields;
any_header_lenient := (arc_header | warc_header_lenient) @{ fbreak; };
any_header := (arc_header | warc_header) @{ fbreak; };

}%%

/**
 * Low-level WARC record parser.
 * <p>
 * Unless you're doing something advanced (like non-blocking IO) you should use the higher-level {@link WarcReader}
 * class instead.
 */
public class WarcParser extends MessageParser {
    private int entryState;
    private int cs;
    private long position;
    private byte[] buf = new byte[256];
    private int bufPos;
    private int endOfText;
    private int major;
    private int minor;
    private String name;
    private String protocol = "WARC";
    private Map<String,List<String>> headerMap;
    private static final DateTimeFormatter arcTimeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static WarcParser newWarcFieldsParser() {
        return new WarcParser(warc_en_warc_fields);
    }

    public WarcParser() {
        this(warc_start);
    }

    private WarcParser(int entryState) {
        this.entryState = entryState;
        reset();
    }

    public void reset() {
        cs = entryState;
        position = 0;
        bufPos = 0;
        endOfText = 0;
        major = 0;
        minor = 0;
        name = null;
        headerMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (buf.length > 4096) {
            buf = new byte[4096];
        }
    }

	/**
	 * Sets the lenient mode for the WarcParser.
	 * <p>
	 * When enabled, this causes the parser to follow the specification less strictly,
	 * allowing reading of non-compliant records by:
	 * <ul>
	 *   <li>permitting ASCII control characters in header field names and values
	 *   <li>allowing lines to end with LF instead of CRLF
	 *   <li>permitting multi-digit WARC minor versions like "0.18"
	 * </ul>
	 * Calling this method also resets the state of the parser.
	 */
    public void setLenient(boolean lenient) {
        if (warcFieldsMode()) {
            entryState = lenient ? warc_en_warc_fields_lenient : warc_en_warc_fields;
        } else {
            entryState = lenient ? warc_en_any_header_lenient : warc_start;
        }
        reset();
    }

    private boolean warcFieldsMode() {
        return entryState == warc_en_warc_fields || entryState == warc_en_warc_fields_lenient;
    }

    public boolean isFinished() {
        return cs >= warc_first_final;
    }

    public boolean isError() {
        return cs == warc_error;
    }

    public void parse(ByteBuffer data) {
        int p = data.position();
        int pe = data.limit();

        %% write exec;

        position += p - data.position();
        data.position(p);
    }

    public boolean parse(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (true) {
            parse(buffer);
            if (isFinished()) {
                return true;
            }
            if (isError()) {
                throw new ParsingException("invalid WARC record at position " + position + ": "
                        + getErrorContext(buffer, buffer.position(), 40));
            }
            buffer.compact();
            int n = channel.read(buffer);
            buffer.flip();
            if (n < 0) {
                if (position > 0) {
                    throw new EOFException();
                }
                return false;
            }
        }
    }

    private void push(byte b) {
        if (bufPos >= buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        buf[bufPos++] = b;
    }

    public MessageHeaders headers() {
        return new MessageHeaders(headerMap);
    }

    public MessageVersion version() {
        return new MessageVersion(protocol, major, minor);
    }

    public long position() {
        return position;
    }

    private void setHeader(String name, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        headerMap.put(name, list);
    }

    %% write data;
}