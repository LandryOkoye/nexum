package com.nexum.memory;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Bedrock embedding model when one is asked for.
 *
 * <p>Conditional on a property rather than a profile so local, test and deployed
 * environments differ in configuration only. With
 * {@code nexum.embedding.provider} unset - the default everywhere except
 * production - no bean is created here, and whatever Spring AI auto-configures
 * (Ollama locally, nothing in tests) is used instead. {@link QueryEmbedder} and
 * {@link EmbeddingWorker} both resolve the model through an
 * {@code ObjectProvider}, so absence is a supported state rather than a startup
 * failure.
 */
@Configuration(proxyBeanMethods = false)
class EmbeddingConfiguration {

    /**
     * The Bedrock client, authenticated by the default provider chain.
     *
     * <p>On EC2 that chain ends at the instance role, which is the only place
     * production credentials exist - there is no key material on the box or in
     * the image.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "nexum.embedding.provider", havingValue = "titan")
    BedrockRuntimeClient bedrockRuntimeClient(
            @Value("${nexum.embedding.region:${AWS_REGION:us-east-1}}") String region) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "nexum.embedding.provider", havingValue = "titan")
    TitanEmbeddingModel titanEmbeddingModel(BedrockRuntimeClient client,
            @Value("${nexum.embedding.model:amazon.titan-embed-text-v2:0}") String modelId,
            @Value("${nexum.embedding.dimensions:1024}") int dimensions) {
        return new TitanEmbeddingModel(client, modelId, dimensions);
    }
}
