package com.nexum.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Amazon Titan Text Embeddings V2, called directly.
 *
 * <p>Written by hand rather than using Spring AI's Bedrock Titan support, which
 * cannot talk to this model. Its {@code TitanEmbeddingBedrockApi} serialises
 * {@code inputImage} and {@code embeddingConfig} alongside {@code inputText} -
 * the schema of the older text-v1 and multimodal image models. Titan v2 rejects
 * both fields, and Bedrock answers every request with
 * {@code Malformed input request: 2 schema violations found}. Deployed, that
 * surfaced as memories that stayed PENDING forever while the application looked
 * entirely healthy.
 *
 * <p>Downgrading to text-v1 would have been the smaller change and is not
 * available: v1 returns 1536 dimensions, the schema declares
 * {@code VECTOR(1024)}, and Invariant 3 fixes that width. The request body here
 * is the one verified against the live service before deploying - inputText,
 * dimensions, normalize, and nothing else.
 *
 * <p>Credentials come from the default provider chain, which on EC2 resolves to
 * the instance role. This is why {@code compose.prod.yaml} deliberately omits
 * {@code AWS_ACCESS_KEY_ID}: any value there, even an empty one, shadows the
 * role and breaks embedding on the deployed box while working on a laptop.
 */
public class TitanEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(TitanEmbeddingModel.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BedrockRuntimeClient client;
    private final String modelId;
    private final int dimensions;

    public TitanEmbeddingModel(BedrockRuntimeClient client, String modelId, int dimensions) {
        this.client = client;
        this.modelId = modelId;
        this.dimensions = dimensions;
    }

    /**
     * Titan embeds one string per call - there is no batch endpoint - so a
     * multi-input request becomes a loop.
     *
     * <p>Acceptable here where it would not be against a local model: Bedrock
     * answers in a couple of hundred milliseconds, against the seconds a
     * CPU-bound local model takes, so the per-call overhead that made batching
     * essential for Ollama is not the dominant cost.
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>(request.getInstructions().size());
        int index = 0;
        for (String text : request.getInstructions()) {
            embeddings.add(new Embedding(invoke(text), index++));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return invoke(document.getText());
    }

    @Override
    public int dimensions() {
        return this.dimensions;
    }

    private float[] invoke(String text) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("inputText", text);
        // Titan v2 can emit 256, 512 or 1024. Stated explicitly rather than
        // relying on the default, because the column is VECTOR(1024) and a
        // silent default change would be caught only by an insert failure.
        body.put("dimensions", this.dimensions);
        // Cosine distance on unnormalised vectors is not the similarity the
        // vector_cosine_ops index assumes. Normalising here keeps the distances
        // shown in the UI meaningful.
        body.put("normalize", true);

        InvokeModelResponse response;
        try {
            response = this.client.invokeModel(InvokeModelRequest.builder()
                    .modelId(this.modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromString(body.toString(), StandardCharsets.UTF_8))
                    .build());
        }
        catch (RuntimeException ex) {
            log.warn("Bedrock embedding call failed for model {}: {}", this.modelId,
                    ex.getMessage());
            throw ex;
        }

        try {
            JsonNode parsed = MAPPER.readTree(response.body().asUtf8String());
            JsonNode vector = parsed.path("embedding");
            if (!vector.isArray()) {
                throw new IllegalStateException(
                        "Titan response had no embedding array: " + parsed.toString());
            }
            float[] result = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                result[i] = (float) vector.get(i).asDouble();
            }
            return result;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not parse the Titan response", ex);
        }
    }
}
