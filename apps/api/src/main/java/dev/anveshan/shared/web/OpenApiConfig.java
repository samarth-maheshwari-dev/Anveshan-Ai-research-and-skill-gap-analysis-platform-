package dev.anveshan.shared.web;

import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI document for the generated web client (TECHSPEC §8.7). A fixed server URL keeps the
 * exported spec identical between runs (random test ports would otherwise leak into it).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	@Bean
	OpenAPI anveshanOpenApi() {
		return new OpenAPI()
				.info(new Info().title("Anveshan API").version("v1")
						.description("Session cookie auth; send X-XSRF-TOKEN on writes. Errors are RFC 9457 Problem Details."))
				.servers(List.of(new Server().url("/")));
	}

	/** Every operation can fail with an RFC 9457 Problem (TECHSPEC §8.4); document its shape once. */
	@Bean
	OpenApiCustomizer problemResponses() {
		return api -> {
			Schema<?> fieldError = new ObjectSchema()
					.properties(Map.of("field", new StringSchema(), "message", new StringSchema()));
			Schema<?> problem = new ObjectSchema()
					.properties(new java.util.TreeMap<>(Map.of(
							"type", new StringSchema(), "title", new StringSchema(), "status", new IntegerSchema(),
							"detail", new StringSchema(), "instance", new StringSchema(), "code", new StringSchema(),
							"errors", new ArraySchema().items(fieldError), "resumeAt", new StringSchema(),
							"slot", new StringSchema())));
			api.getComponents().addSchemas("Problem", problem);
			// Logout is a Spring Security filter, not a controller, so springdoc can't see it.
			api.getPaths().addPathItem("/api/v1/auth/logout", new io.swagger.v3.oas.models.PathItem()
					.post(new io.swagger.v3.oas.models.Operation().operationId("logout").summary("End the session")
							.responses(new io.swagger.v3.oas.models.responses.ApiResponses()
									.addApiResponse("204", new ApiResponse().description("Signed out")))));
			var content = new Content().addMediaType("application/problem+json",
					new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")));
			api.getPaths().values().forEach(item -> item.readOperations().forEach(op -> op.getResponses()
					.addApiResponse("default", new ApiResponse().description("Problem Details").content(content))));
		};
	}

}
