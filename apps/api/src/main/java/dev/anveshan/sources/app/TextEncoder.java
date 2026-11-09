package dev.anveshan.sources.app;

import java.util.List;

/** Raw model call: already-prefixed texts in, normalized vectors out (same order). */
public interface TextEncoder {

	List<float[]> encode(List<String> texts);

}
