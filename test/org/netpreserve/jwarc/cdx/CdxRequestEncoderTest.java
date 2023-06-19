package org.netpreserve.jwarc.cdx;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.netpreserve.jwarc.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

public class CdxRequestEncoderTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    public static final Case[] cases = new Case[]{
            new Case("__wb_method=POST&__wb_post_data=Zm9vPWJhciZkaXI9JTJGYmF6",
                    MediaType.OCTET_STREAM, "foo=bar&dir=%2Fbaz"),
            new Case("__wb_method=POST&foo=bar&dir=/baz",
                    MediaType.WWW_FORM_URLENCODED, "foo=bar&dir=%2Fbaz"),
            new Case("__wb_method=POST&__wb_post_data=/w==",
                    MediaType.WWW_FORM_URLENCODED, new byte[]{-1}),
            new Case("__wb_method=POST&a=b&a.2_=2&d=e",
                    MediaType.JSON, "{\"a\": \"b\", \"c\": {\"a\": 2}, \"d\": \"e\"}"),
            new Case("__wb_method=POST",
                    MediaType.JSON, "not json"),
            new Case("__wb_method=POST&type=event&id=44.0&values=True&values.2_=False&values.3_=None" +
                    "&type.2_=component&id.2_=a%2Bb%26c%3D+d&values.4_=3&values.5_=4",
                    MediaType.JSON, ("{\n" +
                                    "   \"type\": \"event\",\n" +
                                    "   \"id\": 44.0,\n" +
                                    "   \"values\": [true, false, null],\n" +
                                    "   \"source\": {\n" +
                                    "      \"type\": \"component\",\n" +
                                    "      \"id\": \"a+b&c= d\",\n" +
                                    "      \"values\": [3, 4]\n" +
                                    "   }\n" +
                                    "}\n")),
            new Case("__wb_method=POST&a=2",
                    MediaType.PLAIN_TEXT, "{\"a\":2}"),
            new Case("__wb_method=POST&__wb_post_data=bm90IGpzb24=",
                    MediaType.PLAIN_TEXT, "not json")
    };

    public static class Case {
        final String expected;
        final MediaType requestType;
        final byte[] requestBody;

        public Case(String expected, MediaType requestType, String requestBody) {
            this(expected, requestType, requestBody.getBytes(UTF_8));
        }

        public Case(String expected, MediaType requestType, byte[] requestBody) {
            this.expected = expected;
            this.requestType = requestType;
            this.requestBody = requestBody;
        }

        public HttpRequest request() {
            return new HttpRequest.Builder("POST", "/foo")
                    .body(requestType, requestBody)
                    .build();
        }
    }

    @Test
    public void test() throws IOException {
        for (int i = 0; i < cases.length; i++) {
            assertEquals("Case " + i, cases[i].expected, CdxRequestEncoder.encode(cases[i].request()));
        }
    }

    @Test
    public void testAgainstReference() throws IOException {
        String referenceCdxIndexer = System.getenv("CDX_INDEXER");
        assumeTrue("CDX_INDEXER environment variable must be set to test against a reference indexer",
                referenceCdxIndexer != null);
        Path tmp = temporaryFolder.newFile().toPath();
        try (WarcWriter warcWriter = new WarcWriter(Files.newByteChannel(tmp, StandardOpenOption.WRITE))) {
            for (Case testcase : cases) {
                String url = "http://example/" + testcase.request().target();
                WarcRecord response = new WarcResponse.Builder(url)
                        .body(new HttpResponse.Builder(200, "OK")
                                .body(MediaType.PLAIN_TEXT, "hello".getBytes(UTF_8))
                                .build())
                        .build();
                WarcRecord request = new WarcRequest.Builder(url)
                        .body(testcase.request())
                        .concurrentTo(response.id())
                        .build();
                warcWriter.write(response);
                warcWriter.write(request);
            }
        }
        Process p = new ProcessBuilder(referenceCdxIndexer, "-p", tmp.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            reader.readLine(); // ignore header
            for (int i = 0; i < cases.length; i++) {
                String line = reader.readLine();
                String urlkey = line.split(" ")[0];
                String queryString = urlkey.replaceFirst(".*\\?", "");
                String[] params = queryString.split("&");
                Arrays.sort(params);
                String sortedQueryString = String.join("&", params);
                String expectedKey = URIs.toNormalizedSurt("http://example" + cases[i].request().target() + "?" +
                        cases[i].expected).replaceFirst(".*\\?", "");
                assertEquals("Case " + i, expectedKey, sortedQueryString);
            }
        } finally {
            p.destroyForcibly();
        }
    }
}
