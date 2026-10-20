package com.splitledger.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void hashingIsStableAndDiffersPerInput() {
        assertThat(SecureTokens.hash("abc")).isEqualTo(SecureTokens.hash("abc"));
        assertThat(SecureTokens.hash("abc")).isNotEqualTo(SecureTokens.hash("abd"));
    }

    @Test
    void hashIsSixtyFourHexCharactersToFitTheTokenHashColumns() {
        assertThat(SecureTokens.hash(SecureTokens.random()))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void tokensAreUrlSafeSoTheyCanBePastedIntoALinkUnescaped() {
        assertThat(SecureTokens.random()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void tokensDoNotRepeat() {
        // 32 bytes of CSPRNG output; a collision here means the generator is broken.
        assertThat(Stream.generate(SecureTokens::random).limit(500).collect(Collectors.toSet()))
                .hasSize(500);
    }
}
