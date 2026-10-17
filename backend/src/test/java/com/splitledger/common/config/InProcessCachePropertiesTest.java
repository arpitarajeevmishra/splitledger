package com.splitledger.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.splitledger.common.config.InProcessCacheProperties.CacheSpec;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Plain unit test: no Spring context, no database, no Docker. Runs in Surefire. */
class InProcessCachePropertiesTest {

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void aCacheWithoutAPositiveSizeLimitIsRejected(long maximumSize) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheSpec(maximumSize, Duration.ofMinutes(5)))
                .withMessageContaining("maximum-size");
    }

    @Test
    void aCacheWithoutATimeToLiveIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheSpec(10, null))
                .withMessageContaining("time-to-live");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void aNonPositiveTimeToLiveIsRejected(long seconds) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheSpec(10, Duration.ofSeconds(seconds)))
                .withMessageContaining("time-to-live");
    }

    @Test
    void theCachesCannotBeMutatedThroughTheMapThatWasBoundToThem() {
        Map<String, CacheSpec> mutable = new java.util.LinkedHashMap<>();
        mutable.put("bal", new CacheSpec(10, Duration.ofMinutes(5)));
        InProcessCacheProperties properties = new InProcessCacheProperties(mutable);

        mutable.clear();

        assertThat(properties.caches()).containsOnlyKeys("bal");
    }

    @Test
    void absentCachesBindToAnEmptyMapRatherThanNull() {
        assertThat(new InProcessCacheProperties(null).caches()).isEmpty();
    }
}
