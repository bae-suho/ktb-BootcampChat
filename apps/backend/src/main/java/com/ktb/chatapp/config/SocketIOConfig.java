package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.RedisChatDataStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:0.0.0.0}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    // 👉 Redis B 설정 값 주입
    @Value("${socketio.redis.host:localhost}")
    private String redisHost;

    @Value("${socketio.redis.port:6379}")
    private Integer redisPort;

    @Value("${socketio.redis.password:}")
    private String redisPassword;

    /**
     * Redis B용 Redisson 클라이언트
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient socketRedisClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;

        var single = config.useSingleServer();
        single.setAddress(address);
        single.setConnectionMinimumIdleSize(2);
        single.setConnectionPoolSize(10);

        // ⚡ 다중 EC2 환경 타임아웃 설정
        single.setConnectTimeout(10000);  // 연결 타임아웃: 10초
        single.setTimeout(5000);          // 명령 타임아웃: 5초
        single.setRetryAttempts(3);       // 재시도 횟수: 3회
        single.setRetryInterval(1500);    // 재시도 간격: 1.5초

        if (redisPassword != null && !redisPassword.isEmpty()) {
            single.setPassword(redisPassword);
        }

        log.info("Socket Redis(B) Config - host: {}, port: {}, password: {}",
                redisHost, redisPort,
                redisPassword != null && !redisPassword.isEmpty() ? "***" : "none");

        return Redisson.create(config);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener,
                                         RedissonClient socketRedisClient) {

        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);

        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(false);
        socketConfig.setAcceptBackLog(10);
        socketConfig.setTcpSendBufferSize(4096);
        socketConfig.setTcpReceiveBufferSize(4096);
        config.setSocketConfig(socketConfig);

        config.setOrigin("*");

        // Socket.IO settings
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));

        // ✅ 여기서부터가 핵심: 인메모리 → Redis B 기반 RedissonStoreFactory
        config.setStoreFactory(new RedissonStoreFactory(socketRedisClient));

        log.info("Socket.IO server configured on {}:{} with {} boss threads and {} worker threads",
                host, port, config.getBossThreads(), config.getWorkerThreads());

        SocketIOServer socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);

        return socketIOServer;
    }

    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }

    // ✅ ChatDataStore도 Redis B를 사용하도록 변경
    @Bean
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public ChatDataStore chatDataStore(RedissonClient socketRedisClient) {
        return new RedisChatDataStore(socketRedisClient);
    }
}
