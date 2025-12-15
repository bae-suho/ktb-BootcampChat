package com.ktb.chatapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import com.ktb.chatapp.websocket.socketio.RedisChatDataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configuration for ChatDataStore.
 * Allows switching between local (in-memory) and Redis storage.
 */
@Slf4j
@Configuration
public class ChatDataStoreConfig {

    /**
     * Local in-memory ChatDataStore.
     * Used for development or single-node deployments.
     */
    @Bean
    @ConditionalOnProperty(name = "socketio.datastore.type", havingValue = "local", matchIfMissing = true)
    public ChatDataStore localChatDataStore() {
        log.info("╔═══════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                    Socket DataStore: LOCAL (In-Memory)                        ║");
        log.info("╚═══════════════════════════════════════════════════════════════════════════════╝");
        return new LocalChatDataStore();
    }

    /**
     * Redis-backed ChatDataStore.
     * Used for production multi-node deployments.
     */
    @Bean
    @ConditionalOnProperty(name = "socketio.datastore.type", havingValue = "redis")
    public ChatDataStore redisChatDataStore(StringRedisTemplate stringRedisTemplate) {
        log.info("╔═══════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                    Socket DataStore: REDIS (Distributed)                      ║");
        log.info("╚═══════════════════════════════════════════════════════════════════════════════╝");

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        return new RedisChatDataStore(stringRedisTemplate, objectMapper);
    }
}
