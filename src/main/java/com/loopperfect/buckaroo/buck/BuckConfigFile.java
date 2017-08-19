package com.loopperfect.buckaroo.buck;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class BuckConfigFile {

    private final Map<String, Map<String, String>> config = new HashMap<>();

    private static final Pattern REGEX_SECTION_HEADER = Pattern.compile("\\[(.+)\\]");

    private BuckConfigFile() {
    }

    private BuckConfigFile(final String content) {
        // Parse the given content into [section] -> ([key] -> [value]) map
        String currentSection = null;

        for (String line : new BufferedReader(new StringReader(content)).lines().collect(Collectors.toList())) {
            // Skip empty lines
            if (line.trim().isEmpty()) {
                continue;
            }

            // Check if the line is a section header
            Matcher matcher = REGEX_SECTION_HEADER.matcher(line);
            if (matcher.matches()) {
                currentSection = matcher.group(1);

                if (!config.containsKey(currentSection)) {
                    config.put(currentSection, new HashMap<>());
                }

                continue;
            }

            // Otherwise the line is a key=value entry
            if (currentSection == null) {
                // TODO: An key=value entry appeared before any section header. This should be an error condition right?
                continue;
            }

            String[] parts = line.split("=", 2);
            String key = parts[0].trim();
            String value = parts[1].trim();

            config.get(currentSection).put(key, value);
        }
    }

    private void include(final BuckConfigFile other) {
        for (String section : other.config.keySet()) {
            if (!config.containsKey(section)) {
                config.put(section, new HashMap<>());
            }

            for (String key : other.config.get(section).keySet()) {
                config.get(section).put(key, other.config.get(section).get(key));
            }
        }
    }

    public String dumpContent() {
        StringBuilder content = new StringBuilder();

        for (String section : config.keySet()) {
            content.append("[" + section + "]" + "\n");

            for (String key : config.get(section).keySet()) {
                String value = config.get(section).get(key);
                content.append("  " + key + " = " + value + "\n");
            }

            content.append("\n");
        }

        return content.toString();
    }

    public static BuckConfigFile of(final String content) {
        return new BuckConfigFile(content);
    }

    public static BuckConfigFile merge(final BuckConfigFile base, final BuckConfigFile override) {
        BuckConfigFile merged = new BuckConfigFile();

        merged.include(base);
        merged.include(override);

        return merged;
    }

}
