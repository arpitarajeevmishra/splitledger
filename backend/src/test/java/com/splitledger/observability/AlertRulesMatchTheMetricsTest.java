package com.splitledger.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The committed alert rules have to name metrics that exist.
 *
 * <p>A Prometheus rule referring to a metric nobody emits is not an error anywhere. It
 * evaluates to no data, fires never, and looks exactly like a system that is healthy --
 * which for {@code LedgerReconciliationDrift} would mean the arithmetic every number in
 * this product rests on was being watched by nothing at all.
 *
 * <p>Two different mistakes, caught in two places. A metric <em>renamed in the code</em> is
 * caught by that component's own test, which pins the string it registers under. A metric
 * <em>mistyped in the YAML</em> is caught here. Neither test catches the other's failure,
 * which is why both exist.
 */
class AlertRulesMatchTheMetricsTest {

    private static final Path RULES = Path.of("..", "deploy", "observability", "alerts.yml");

    /** Micrometer's dotted names as Prometheus renders them; a counter gains {@code _total}. */
    private static final Set<String> EMITTED = Set.of(
            "splitledger_ledger_reconciliation_drift_paise",
            "splitledger_outbox_lag_seconds",
            "splitledger_outbox_dead_lettered",
            "splitledger_webhook_deliveries_total");

    private static final Pattern SERIES = Pattern.compile("\\bsplitledger_[a-z_]+\\b");

    private static Set<String> namedInTheRules() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SERIES.matcher(Files.readString(RULES));
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }

    @Test
    void the_rules_file_is_where_this_test_thinks_it_is() throws IOException {
        // Otherwise a moved file turns both tests below into assertions about an empty
        // set, which pass.
        assertThat(RULES).exists();
        assertThat(namedInTheRules()).isNotEmpty();
    }

    @Test
    void every_series_the_rules_mention_is_one_the_application_emits() throws IOException {
        assertThat(namedInTheRules()).allSatisfy(series ->
                assertThat(EMITTED)
                        .as("alerts.yml alerts on %s, which nothing emits", series)
                        .contains(series));
    }

    @Test
    void the_metric_that_matters_most_is_actually_alerted_on() throws IOException {
        // Named explicitly rather than left to the set comparison above, which would still
        // pass if every rule about the ledger were deleted. A refactor that quietly dropped
        // this one would leave a green suite and an unwatched ledger.
        assertThat(namedInTheRules())
                .contains("splitledger_ledger_reconciliation_drift_paise");
    }
}
