package com.chessedu.platform.identity.invitation;

import com.chessedu.platform.security.TokenHasher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Test-only support: we need to recover the raw invitation token so we can
 * hit the public accept endpoint with it, but the production service only
 * persists the SHA-256 hash. A spy {@link TokenHasher} captures every
 * mint/hash pair into {@link MintedTokenRegistry}; tests read back by hash.
 */
@TestConfiguration
public class InvitationTestSupport {

    @Bean
    public MintedTokenRegistry mintedTokenRegistry() {
        return new MintedTokenRegistry();
    }

    @Bean
    @Primary
    public TokenHasher capturingTokenHasher(MintedTokenRegistry registry) {
        TokenHasher delegate = new TokenHasher();
        return new TokenHasher() {
            @Override
            public String mint() {
                String raw = delegate.mint();
                registry.put(delegate.hash(raw), raw);
                return raw;
            }

            @Override
            public String hash(String token) {
                return delegate.hash(token);
            }
        };
    }

    public static class MintedTokenRegistry {
        private final ConcurrentMap<String, String> byHash = new ConcurrentHashMap<>();

        public void put(String hash, String raw) {
            byHash.put(hash, raw);
        }

        public String byHash(String hash) {
            return byHash.get(hash);
        }
    }
}
