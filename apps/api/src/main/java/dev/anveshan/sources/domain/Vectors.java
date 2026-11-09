package dev.anveshan.sources.domain;

/** Pure vector math for embeddings. No Spring, no IO. */
public final class Vectors {

	private Vectors() {
	}

	/** Average of the token vectors whose attention-mask value is 1 (padding is ignored). */
	public static float[] meanPool(float[][] tokens, long[] mask) {
		if (tokens.length == 0) {
			throw new IllegalArgumentException("no tokens");
		}
		int dim = tokens[0].length;
		float[] sum = new float[dim];
		int count = 0;
		for (int t = 0; t < tokens.length && t < mask.length; t++) {
			if (mask[t] == 0) {
				continue;
			}
			count++;
			for (int d = 0; d < dim; d++) {
				sum[d] += tokens[t][d];
			}
		}
		if (count == 0) {
			throw new IllegalArgumentException("attention mask is all zeros");
		}
		for (int d = 0; d < dim; d++) {
			sum[d] /= count;
		}
		return sum;
	}

	/** Returns a new vector with Euclidean length 1. */
	public static float[] l2Normalize(float[] v) {
		double norm = norm(v);
		if (norm == 0) {
			throw new IllegalArgumentException("zero vector");
		}
		float[] out = new float[v.length];
		for (int i = 0; i < v.length; i++) {
			out[i] = (float) (v[i] / norm);
		}
		return out;
	}

	public static double norm(float[] v) {
		double s = 0;
		for (float x : v) {
			s += (double) x * x;
		}
		return Math.sqrt(s);
	}

	public static double cosine(float[] a, float[] b) {
		if (a.length != b.length) {
			throw new IllegalArgumentException("dimension mismatch: " + a.length + " vs " + b.length);
		}
		double dot = 0;
		for (int i = 0; i < a.length; i++) {
			dot += (double) a[i] * b[i];
		}
		return dot / (norm(a) * norm(b));
	}

}
