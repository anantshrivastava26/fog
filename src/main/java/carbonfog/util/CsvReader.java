package carbonfog.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the CSVs written by {@link CsvWriter} back into ordered maps (numbers parsed). */
public final class CsvReader {
    private CsvReader() { }

    public static List<Map<String, Object>> read(Path file) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.exists(file)) return rows;
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) return rows;
        List<String> header = split(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> cells = split(lines.get(i));
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c), c < cells.size() ? parse(cells.get(c)) : null);
            rows.add(row);
        }
        return rows;
    }

    private static Object parse(String s) {
        if (s.isEmpty()) return null;
        if (s.equals("true") || s.equals("false")) return Boolean.parseBoolean(s);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
