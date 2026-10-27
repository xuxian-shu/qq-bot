package com.qqbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 覆盖 Spring Boot 自动配置的 RestClient.Builder。
 * <p>
 * Boot 3.2 在 classpath 上发现 OkHttp 后，会用它给 RestClient 建请求工厂，
 * 而 OkHttp 的默认读超时只有 10 秒。本地大模型一次推理经常超过 10 秒，
 * 于是客户端在 10 秒时主动断开，Ollama 侧报 "aborting chat request due to
 * client closing the connection"，Spring AI 抛异常，AI 回复就变成了失败提示。
 * <p>
 * 这里显式放宽读超时，给本地模型留足推理时间；Spring AI 的 OllamaApi
 * 会注入容器里的这个 Builder。
 * <p>
 * 请求工厂用 JDK 自带的 HttpClient，而不是 OkHttp：
 * OkHttp3ClientHttpRequestFactory 自 Spring 6.1 起废弃并标记移除（Spring 7 删除），
 * 没必要在一个新类里继续依赖它。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))//连接本地 Ollama 很快，10 秒足够
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(120));//等模型推理，放宽到 2 分钟

        return RestClient.builder()
                .requestFactory(factory);
    }
}
