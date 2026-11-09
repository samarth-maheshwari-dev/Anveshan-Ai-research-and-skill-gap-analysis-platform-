package dev.anveshan.byok.app;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal JSON Schema for the output records we ask LLMs to produce: records, strings, numbers,
 * booleans, enums, lists and nested records. Every record field is required unless it's an
 * {@link Optional}. Cached per type.
 */
@Component
public class JsonSchemas {

	private final JsonMapper json;
	private final Map<Class<?>, String> cache = new ConcurrentHashMap<>();

	JsonSchemas(JsonMapper json) {
		this.json = json;
	}

	public String schemaFor(Class<?> type) {
		return cache.computeIfAbsent(type, t -> json.writeValueAsString(schema(t, 0)));
	}

	static Map<String, Object> schema(Type type, int depth) {
		if (depth > 8) {
			throw new IllegalArgumentException("Output type is nested too deeply");
		}
		if (type instanceof ParameterizedType p) {
			Class<?> raw = (Class<?>) p.getRawType();
			if (Collection.class.isAssignableFrom(raw)) {
				return Map.of("type", "array", "items", schema(p.getActualTypeArguments()[0], depth + 1));
			}
			if (raw == Optional.class) {
				return schema(p.getActualTypeArguments()[0], depth + 1);
			}
			if (Map.class.isAssignableFrom(raw)) {
				return Map.of("type", "object", "additionalProperties", schema(p.getActualTypeArguments()[1], depth + 1));
			}
		}
		Class<?> c = (Class<?>) type;
		if (c == String.class) {
			return Map.of("type", "string");
		}
		if (c == boolean.class || c == Boolean.class) {
			return Map.of("type", "boolean");
		}
		if (c == int.class || c == Integer.class || c == long.class || c == Long.class || c == short.class
				|| c == BigInteger.class) {
			return Map.of("type", "integer");
		}
		if (c == double.class || c == Double.class || c == float.class || c == Float.class || c == BigDecimal.class) {
			return Map.of("type", "number");
		}
		if (c.isEnum()) {
			List<String> values = new ArrayList<>();
			for (Object e : c.getEnumConstants()) {
				values.add(((Enum<?>) e).name());
			}
			return Map.of("type", "string", "enum", values);
		}
		if (c.isRecord()) {
			Map<String, Object> props = new LinkedHashMap<>();
			List<String> required = new ArrayList<>();
			for (RecordComponent rc : c.getRecordComponents()) {
				props.put(rc.getName(), schema(rc.getGenericType(), depth + 1));
				if (rc.getType() != Optional.class) {
					required.add(rc.getName());
				}
			}
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("type", "object");
			s.put("properties", props);
			s.put("required", required);
			s.put("additionalProperties", false);
			return s;
		}
		throw new IllegalArgumentException("Unsupported output type: " + c.getName());
	}

}
