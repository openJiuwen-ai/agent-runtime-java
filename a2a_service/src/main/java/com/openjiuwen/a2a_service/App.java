package com.openjiuwen.a2a_service;

import com.openjiuwen.a2a_service.agents.EDPAgent.Agent;
import com.openjiuwen.a2a_service.common.RedisClient;
import com.openjiuwen.a2a_service.common.RedisTaskStore;
import com.openjiuwen.a2a_service.config.DPASettings;
import com.openjiuwen.a2a_service.config.Settings;
import com.openjiuwen.a2a_service.orchestrator.Executor;
import com.openjiuwen.a2a_service.orchestrator.UserRouter;
import io.a2a.client.Client;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.TransportProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * A2A Service 进程入口。
 *
 * 暴露端点：
 *   POST /v1/{project_id}/agents/{agent_id}/conversations/{conv_id}  — 定制化 Versatile 入口
 *   GET  /a2a/.well-known/agent-card.json                            — A2A 标准 Agent Card
 *   POST /a2a/                                                        — A2A 标准 JSON-RPC 入口
 *
 * 两条路径共用同一个 Executor + RedisTaskStore，Task 状态一致。
 */
@SpringBootApplication
@EnableConfigurationProperties({Settings.class, DPASettings.class})
public class App {

    private static final Logger logger = LoggerFactory.getLogger(App.class);
    private static final int TTL = 1800;

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(Settings settings) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(settings.getRedisHost());
        config.setPort(settings.getRedisPort());
        config.setDatabase(settings.getRedisDb());
        if (settings.getRedisPassword() != null && !settings.getRedisPassword().isEmpty()) {
            config.setPassword(settings.getRedisPassword());
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisClient redisClient(StringRedisTemplate template) {
        return new RedisClient(template);
    }

    @Bean
    public RedisTaskStore redisTaskStore(RedisClient redisClient, Settings settings) {
        return new RedisTaskStore(redisClient, settings.getRedisSessionTtl() > 0 ? settings.getRedisSessionTtl() : TTL);
    }

    @Bean
    public Executor executor(RedisClient redisClient, RedisTaskStore taskStore, Settings settings) {
        // 创建 A2A Client 连接 VersatileAdapter
        Client vaClient;
        try {
            String vaUrl = settings.getVersatileAdapterUrl();

            // 手动构建 VA AgentCard（与 Python 版 _build_va_card 对齐）
            AgentCard vaCard = AgentCard.builder()
                    .name("VersatileAdapter")
                    .description("Versatile 低代码平台 A2A 适配器")
                    .version("1.0.0")
                    .capabilities(AgentCapabilities.builder()
                            .streaming(true)
                            .build())
                    .supportedInterfaces(List.of(
                            new AgentInterface(TransportProtocol.JSONRPC.asString(), vaUrl)))
                    .defaultInputModes(List.of("text/plain"))
                    .defaultOutputModes(List.of("text/plain"))
                    .skills(List.of())
                    .build();

            ClientConfig clientConfig = ClientConfig.builder()
                    .setStreaming(true)
                    .build();

            vaClient = Client.builder(vaCard)
                    .withTransport(JSONRPCTransport.class,
                            new JSONRPCTransportConfigBuilder().build())
                    .clientConfig(clientConfig)
                    .streamingErrorHandler(err ->
                            logger.warn("[A2AService] VA stream error: {}", err.getMessage()))
                    .build();

            logger.info("[A2AService] A2A Client 创建成功，VersatileAdapter={}", vaUrl);
        } catch (Exception e) {
            logger.error("[A2AService] A2A Client 创建失败，将使用空客户端: {}", e.getMessage());
            vaClient = null;
        }

        return new Executor(vaClient, redisClient, taskStore, settings);
    }

    @Bean
    public UserRouter userRouter(Executor executor, RedisClient redisClient, Settings settings) {
        return new UserRouter(executor, redisClient, settings);
    }

    /**
     * 应用启动后初始化 DPA Agent。
     */
    @Bean
    public Object dpaInitializer(DPASettings dpaSettings) {
        logger.info("[A2AService] 开始初始化 Agent...");
        Agent.initializeDpa(dpaSettings);
        logger.info("[A2AService] Agent 初始化完成");
        return new Object(); // placeholder
    }
}
