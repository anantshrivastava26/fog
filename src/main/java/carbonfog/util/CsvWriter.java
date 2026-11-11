package carbonfog.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Minimal CSV writer for lists of ordered maps. */
public final class CsvWriter {
    private CsvWriter() { }

    public static void write(Path file, List<Map<String, Object>> rows) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Set<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) cols.addAll(r.keySet());
        List<String> header = new ArrayList<>(cols);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println(String.join(",", header));
            for (Map<String, Object> r : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < header.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(format(r.get(header.get(i))));
                }
                w.println(sb);
            }
        }
    }

    public static void writeLines(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines);
    }

    static String format(Object v) {
        if (v == null) return "";
        if (v instanceof Double d) return String.format(Locale.ROOT, "%.4f", d);
        if (v instanceof Float f) return String.format(Locale.ROOT, "%.4f", f);
        String s = v.toString();
        if (s.contains(",") || s.contains("\"")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
