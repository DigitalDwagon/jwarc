package org.netpreserve.jwarc.tools;

import org.netpreserve.jwarc.*;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Comparator.comparing;

public class StatsTool {
    private static class Row {
        final String key;
        long count;
        long totalSize;

        Row(String key) {
            this.key = key;
        }

        public void add(long size) {
            count++;
            totalSize += size;
        }
    }

    private static class Table {
        final String name;
        final Function<WarcRecord, String> keyFunction;
        final Map<String, Row> rows = new HashMap<>();

        private Table(String name, Function<WarcRecord, String> keyFunction) {
            this.name = name;
            this.keyFunction = keyFunction;
        }

        public void add(WarcRecord record, long size) {
            String key = keyFunction.apply(record);
            if (key == null) return;
            rows.computeIfAbsent(key, Row::new).add(size);
        }

        public void print() {
            if (rows.isEmpty()) return;
            int maxKeyLength = rows.keySet().stream().mapToInt(String::length).max().orElse(10);
            System.out.printf("%-" + maxKeyLength + "s %10s %10s %10s%n", name, "COUNT", "TOTSIZE", "AVGSIZE");
            rows.values().stream().sorted(comparing(e -> -e.count)).forEachOrdered(row ->
                    System.out.printf("%-" + maxKeyLength + "s %10d %10d %10d%n",
                            row.key, row.count, row.totalSize, row.totalSize / row.count));
            System.out.println();
        }
    }

    public static void main(String[] args) throws IOException {
        List<Table> tables = Arrays.asList(
                new Table("RECORD", WarcRecord::type),
                new Table("MIME", record -> {
                    if (record instanceof WarcResponse || record instanceof WarcResource) {
                        try {
                            return ((WarcCaptureRecord)record).payload()
                                    .map(payload -> payload.type().base().toString()).orElse(null);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    return null;
                }),
                new Table("HOST", record -> {
                    if (record instanceof WarcResponse || record instanceof WarcResource) {
                        return ((WarcCaptureRecord) record).targetURI().getHost();
                    } else {
                        return  null;
                    }
                })
        );

        for (String arg: args) {
            try (WarcReader reader = new WarcReader(Paths.get(arg))) {
                WarcRecord next = reader.next().orElse(null);
                while (next != null) {
                    long position = reader.position();
                    WarcRecord record = next;
                    if (record instanceof WarcCaptureRecord) {
                        // ensure http headers are parsed before moving to the next record
                        ((WarcCaptureRecord) record).payload();
                    }
                    next = reader.next().orElse(null);
                    long length = reader.position() - position;
                    for (Table table: tables) {
                        table.add(record, length);
                    }
                }
            }
        }

        tables.forEach(Table::print);
    }
}
