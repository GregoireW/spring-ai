/*
 * Copyright 2024 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.vertexai.embedding.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationDocumentation;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.observation.conventions.AiProvider;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.vertexai.embedding.VertexAiEmbeddingConnectionDetails;
import org.springframework.ai.vertexai.embedding.VertexAiEmbeddingUsage;
import org.springframework.ai.vertexai.embedding.VertexAiEmbeddingUtils;
import org.springframework.ai.vertexai.embedding.VertexAiEmbeddingUtils.TextInstanceBuilder;
import org.springframework.ai.vertexai.embedding.VertexAiEmbeddingUtils.TextParametersBuilder;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.google.cloud.aiplatform.v1.EndpointName;
import com.google.cloud.aiplatform.v1.PredictRequest;
import com.google.cloud.aiplatform.v1.PredictResponse;
import com.google.cloud.aiplatform.v1.PredictionServiceClient;
import com.google.protobuf.Value;

import io.micrometer.observation.ObservationRegistry;

/**
 * A class representing a Vertex AI Text Embedding Model.
 *
 * @author Christian Tzolov
 * @author Mark Pollack
 * @since 1.0.0
 */
public class VertexAiTextEmbeddingModel extends AbstractEmbeddingModel {

	private static final EmbeddingModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultEmbeddingModelObservationConvention();

	public final VertexAiTextEmbeddingOptions defaultOptions;

	private final VertexAiEmbeddingConnectionDetails connectionDetails;

	private final RetryTemplate retryTemplate;

	/**
	 * Observation registry used for instrumentation.
	 */
	private final ObservationRegistry observationRegistry;

	/**
	 * Conventions to use for generating observations.
	 */
	private EmbeddingModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	public VertexAiTextEmbeddingModel(VertexAiEmbeddingConnectionDetails connectionDetails,
			VertexAiTextEmbeddingOptions defaultEmbeddingOptions) {
		this(connectionDetails, defaultEmbeddingOptions, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	public VertexAiTextEmbeddingModel(VertexAiEmbeddingConnectionDetails connectionDetails,
			VertexAiTextEmbeddingOptions defaultEmbeddingOptions, RetryTemplate retryTemplate) {
		this(connectionDetails, defaultEmbeddingOptions, retryTemplate, ObservationRegistry.NOOP);
	}

	public VertexAiTextEmbeddingModel(VertexAiEmbeddingConnectionDetails connectionDetails,
			VertexAiTextEmbeddingOptions defaultEmbeddingOptions, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry) {
		Assert.notNull(defaultEmbeddingOptions, "VertexAiTextEmbeddingOptions must not be null");
		Assert.notNull(retryTemplate, "retryTemplate must not be null");
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		this.defaultOptions = defaultEmbeddingOptions.initializeDefaults();
		this.connectionDetails = connectionDetails;
		this.retryTemplate = retryTemplate;
		this.observationRegistry = observationRegistry;
	}

	@Override
	public float[] embed(Document document) {
		Assert.notNull(document, "Document must not be null");
		return this.embed(document.getFormattedContent());
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {

		final VertexAiTextEmbeddingOptions finalOptions = mergedOptions(request);

		var observationContext = EmbeddingModelObservationContext.builder()
			.embeddingRequest(request)
			.provider(AiProvider.VERTEX_AI.value())
			.requestOptions(finalOptions)
			.build();

		return EmbeddingModelObservationDocumentation.EMBEDDING_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				PredictionServiceClient client = createPredictionServiceClient();

				EndpointName endpointName = this.connectionDetails.getEndpointName(finalOptions.getModel());

				PredictRequest.Builder predictRequestBuilder = getPredictRequestBuilder(request, endpointName,
						finalOptions);

				PredictResponse embeddingResponse = retryTemplate
					.execute(context -> getPredictResponse(client, predictRequestBuilder));

				int index = 0;
				int totalTokenCount = 0;
				List<Embedding> embeddingList = new ArrayList<>();
				for (Value prediction : embeddingResponse.getPredictionsList()) {
					Value embeddings = prediction.getStructValue().getFieldsOrThrow("embeddings");
					Value statistics = embeddings.getStructValue().getFieldsOrThrow("statistics");
					Value tokenCount = statistics.getStructValue().getFieldsOrThrow("token_count");
					totalTokenCount = totalTokenCount + (int) tokenCount.getNumberValue();

					Value values = embeddings.getStructValue().getFieldsOrThrow("values");

					float[] vectorValues = VertexAiEmbeddingUtils.toVector(values);

					embeddingList.add(new Embedding(vectorValues, index++));
				}
				EmbeddingResponse response = new EmbeddingResponse(embeddingList,
						generateResponseMetadata(finalOptions.getModel(), totalTokenCount));

				observationContext.setResponse(response);

				return response;
			});
	}

	private VertexAiTextEmbeddingOptions mergedOptions(EmbeddingRequest request) {

		VertexAiTextEmbeddingOptions mergedOptions = this.defaultOptions;

		if (request.getOptions() != null && request.getOptions() != EmbeddingOptions.EMPTY) {
			var defaultOptionsCopy = VertexAiTextEmbeddingOptions.builder().from(this.defaultOptions).build();
			mergedOptions = ModelOptionsUtils.merge(request.getOptions(), defaultOptionsCopy,
					VertexAiTextEmbeddingOptions.class);
		}

		return mergedOptions;
	}

	protected PredictRequest.Builder getPredictRequestBuilder(EmbeddingRequest request, EndpointName endpointName,
			VertexAiTextEmbeddingOptions finalOptions) {
		PredictRequest.Builder predictRequestBuilder = PredictRequest.newBuilder().setEndpoint(endpointName.toString());

		TextParametersBuilder parametersBuilder = TextParametersBuilder.of();

		if (finalOptions.getAutoTruncate() != null) {
			parametersBuilder.withAutoTruncate(finalOptions.getAutoTruncate());
		}

		if (finalOptions.getDimensions() != null) {
			parametersBuilder.withOutputDimensionality(finalOptions.getDimensions());
		}

		predictRequestBuilder.setParameters(VertexAiEmbeddingUtils.valueOf(parametersBuilder.build()));

		for (int i = 0; i < request.getInstructions().size(); i++) {

			TextInstanceBuilder instanceBuilder = TextInstanceBuilder.of(request.getInstructions().get(i))
				.withTaskType(finalOptions.getTaskType().name());
			if (StringUtils.hasText(finalOptions.getTitle())) {
				instanceBuilder.withTitle(finalOptions.getTitle());
			}
			predictRequestBuilder.addInstances(VertexAiEmbeddingUtils.valueOf(instanceBuilder.build()));
		}
		return predictRequestBuilder;
	}

	// for testing
	PredictionServiceClient createPredictionServiceClient() {
		try {
			return PredictionServiceClient.create(this.connectionDetails.getPredictionServiceSettings());
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	// for testing
	PredictResponse getPredictResponse(PredictionServiceClient client, PredictRequest.Builder predictRequestBuilder) {
		PredictResponse embeddingResponse = client.predict(predictRequestBuilder.build());
		return embeddingResponse;
	}

	private EmbeddingResponseMetadata generateResponseMetadata(String model, Integer totalTokens) {
		EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
		metadata.setModel(model);
		Usage usage = new VertexAiEmbeddingUsage(totalTokens);
		metadata.setUsage(usage);
		return metadata;
	}

	@Override
	public int dimensions() {
		return KNOWN_EMBEDDING_DIMENSIONS.getOrDefault(this.defaultOptions.getModel(), super.dimensions());
	}

	private static final Map<String, Integer> KNOWN_EMBEDDING_DIMENSIONS = Stream
		.of(VertexAiTextEmbeddingModelName.values())
		.collect(Collectors.toMap(VertexAiTextEmbeddingModelName::getName,
				VertexAiTextEmbeddingModelName::getDimensions));

	/**
	 * Use the provided convention for reporting observation data
	 * @param observationConvention The provided convention
	 */
	public void setObservationConvention(EmbeddingModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

}