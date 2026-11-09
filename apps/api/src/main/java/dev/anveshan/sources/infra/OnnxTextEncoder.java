package dev.anveshan.sources.infra;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.anveshan.sources.app.TextEncoder;
import dev.anveshan.sources.domain.Vectors;

/**
 * multilingual-e5-small via ONNX Runtime, in-JVM, CPU only (TECHSPEC §12.5).
 * Model files are read from disk; nothing is downloaded at runtime.
 * One batch runs at a time with {@code threads} intra-op threads, so embeddings can
 * never use more than {@code threads} cores.
 */
public final class OnnxTextEncoder implements TextEncoder, AutoCloseable {

	static final int MAX_TOKENS = 512;
	private static final long PAD_ID = 1; // XLM-R <pad>; masked out anyway

	private final OrtEnvironment env;
	private final OrtSession session;
	private final HuggingFaceTokenizer tokenizer;
	private final boolean needsTokenTypes;
	private final Semaphore oneBatchAtATime = new Semaphore(1, true);

	public OnnxTextEncoder(Path modelDir, int threads) {
		Path model = modelDir.resolve("model.onnx");
		Path tokenizerFile = modelDir.resolve("tokenizer.json");
		if (!Files.isRegularFile(model) || !Files.isRegularFile(tokenizerFile)) {
			throw new IllegalStateException("Embedding model not found in " + modelDir
					+ " — run apps/api/scripts/fetch-model.sh (or set ANV_EMBEDDINGS_MODEL_DIR)");
		}
		System.setProperty("ai.djl.offline", "true"); // never let DJL reach the network
		try {
			this.env = OrtEnvironment.getEnvironment();
			var options = new OrtSession.SessionOptions();
			options.setIntraOpNumThreads(threads);
			options.setInterOpNumThreads(1);
			options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
			this.session = env.createSession(model.toString(), options);
			this.needsTokenTypes = session.getInputNames().contains("token_type_ids");
			this.tokenizer = HuggingFaceTokenizer.builder()
					.optTokenizerPath(tokenizerFile)
					.optMaxLength(MAX_TOKENS)
					.optTruncation(true)
					.optPadding(false)
					.build();
		}
		catch (Exception e) {
			throw new IllegalStateException("Could not load embedding model from " + modelDir, e);
		}
	}

	@Override
	public List<float[]> encode(List<String> texts) {
		if (texts.isEmpty()) {
			return List.of();
		}
		oneBatchAtATime.acquireUninterruptibly();
		try {
			return run(texts);
		}
		catch (OrtException e) {
			throw new IllegalStateException("Embedding inference failed", e);
		}
		finally {
			oneBatchAtATime.release();
		}
	}

	private List<float[]> run(List<String> texts) throws OrtException {
		Encoding[] encodings = tokenizer.batchEncode(texts);
		int batch = encodings.length;
		int seq = 0;
		for (Encoding e : encodings) {
			seq = Math.max(seq, e.getIds().length);
		}

		long[] ids = new long[batch * seq];
		long[] mask = new long[batch * seq];
		long[][] masks = new long[batch][seq];
		for (int b = 0; b < batch; b++) {
			long[] tokenIds = encodings[b].getIds();
			long[] attention = encodings[b].getAttentionMask();
			for (int t = 0; t < seq; t++) {
				boolean real = t < tokenIds.length;
				ids[b * seq + t] = real ? tokenIds[t] : PAD_ID;
				mask[b * seq + t] = real ? attention[t] : 0;
				masks[b][t] = mask[b * seq + t];
			}
		}

		long[] shape = { batch, seq };
		Map<String, OnnxTensor> inputs = new HashMap<>();
		try {
			inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape));
			inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));
			if (needsTokenTypes) {
				inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(new long[batch * seq]), shape));
			}
			try (OrtSession.Result result = session.run(inputs)) {
				var output = result.get("last_hidden_state").orElse(result.get(0));
				float[][][] hidden = (float[][][]) output.getValue();
				List<float[]> vectors = new ArrayList<>(batch);
				for (int b = 0; b < batch; b++) {
					vectors.add(Vectors.l2Normalize(Vectors.meanPool(hidden[b], masks[b])));
				}
				return vectors;
			}
		}
		finally {
			inputs.values().forEach(OnnxTensor::close);
		}
	}

	@Override
	public void close() throws OrtException {
		tokenizer.close();
		session.close();
	}

}
