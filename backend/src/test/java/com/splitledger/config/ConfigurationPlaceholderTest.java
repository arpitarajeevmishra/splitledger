package com.splitledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Every {@code app.*} placeholder the code depends on must exist in application.yml.
 *
 * <p>This exists because a property that moved one block up the file -- {@code
 * refresh-grace} written under {@code app.jwt} instead of {@code app.auth} -- compiles,
 * passes every unit test, and then fails the whole application context on startup. With
 * no local database the only thing that loads a context is CI, so the feedback for a
 * typo in a YAML path was a red pipeline fifteen minutes later (O-21).
 *
 * <p>Placeholders carrying a default are skipped deliberately: {@code ${app.x:false}}
 * works whether or not the key is declared, which is the point of writing a default.
 */
class ConfigurationPlaceholderTest {

    /** {@code ${app.something.else}} with no {@code :default} part. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{(app\\.[A-Za-z0-9.\\-]+)}");

    @Test
    void everyAppPropertyTheCodeReadsIsDeclared() throws IOException {
        Map<String, Object> declared = declaredProperties();
        Set<String> referenced = referencedProperties();

        // A guard that checked nothing would pass just as quietly as a correct one.
        assertThat(referenced).hasSizeGreaterThan(20);
        assertThat(declared).containsKeys("app.auth.refresh-ttl", "app.auth.refresh-grace");

        assertThat(referenced)
                .allSatisfy(key -> assertThat(declared)
                        .withFailMessage("%s is read by the code but declared nowhere in "
                                + "application.yml -- check the block it sits under", key)
                        .containsKey(key));
    }

    private Set<String> referencedProperties() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                Matcher matcher = PLACEHOLDER.matcher(Files.readString(source));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    private Map<String, Object> declaredProperties() throws IOException {
        Map<String, Object> flat = new TreeMap<>();
        try (InputStream yaml = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            // loadAll, because the profile-specific documents after each --- declare keys too.
            for (Object document : new Yaml().loadAll(yaml)) {
                flatten("", document, flat);
            }
        }
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Object node, Map<String, Object> into) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) ->
                    flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key,
                            value, into));
            return;
        }
        into.put(prefix, node);
    }
}
