package de.markusfisch.android.shadereditor.spike;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * SPIKE, throwaway: timing helpers for the code-knowledge study. Not for
 * merging. Every number is the result of a warm-up followed by repeated
 * runs; the median, the 90th percentile and the worst run are reported.
 */
final class Bench {
	// The desktop JVM's management beans, by reflection: the Android
	// classes the unit tests compile against do not have them.
	private static final Object THREADS = bean("getThreadMXBean");
	private static final Method ALLOCATED = method(THREADS,
			"com.sun.management.ThreadMXBean", "getThreadAllocatedBytes",
			long.class);

	private static Object bean(String getter) {
		try {
			return Class.forName("java.lang.management.ManagementFactory")
					.getMethod(getter).invoke(null);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static Method method(Object bean, String type, String name,
			Class<?>... parameters) {
		try {
			return bean == null ? null
					: Class.forName(type).getMethod(name, parameters);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	// Bytes the thread has allocated so far, or 0 if it cannot be told.
	private static long allocated(long thread) {
		try {
			return ALLOCATED == null ? 0
					: (Long) ALLOCATED.invoke(THREADS, thread);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	/** Sink that keeps the JIT from removing a computation. */
	static volatile Object sink;

	record Stats(double medianMs, double p90Ms, double maxMs,
			double minMs, long allocBytes, int runs) {
		String ms() {
			return fmt(medianMs) + " (p90 " + fmt(p90Ms) + ", max " +
					fmt(maxMs) + ")";
		}

		String us() {
			return fmtUs(medianMs) + " (p90 " + fmtUs(p90Ms) + ", max " +
					fmtUs(maxMs) + ")";
		}

		String mb() {
			return String.format(java.util.Locale.ROOT, "%.2f MB", allocBytes / 1048576.0);
		}
	}

	private Bench() {
	}

	static String fmt(double ms) {
		return ms >= 100 ? String.format(java.util.Locale.ROOT, "%.0f ms", ms)
				: ms >= 10 ? String.format(java.util.Locale.ROOT, "%.1f ms", ms)
				: String.format(java.util.Locale.ROOT, "%.2f ms", ms);
	}

	static String fmtUs(double ms) {
		double us = ms * 1000;
		return us >= 100 ? String.format(java.util.Locale.ROOT, "%.0f us", us)
				: us >= 10 ? String.format(java.util.Locale.ROOT, "%.1f us", us)
				: String.format(java.util.Locale.ROOT, "%.2f us", us);
	}

	/**
	 * Run work for at least warmMillis, then runs times; the allocation
	 * is the median of the bytes this thread allocated per run.
	 */
	static Stats time(Supplier<Object> work, long warmMillis, int runs) {
		long until = System.nanoTime() + warmMillis * 1_000_000;
		int warm = 0;
		while (System.nanoTime() < until || warm < 5) {
			sink = work.get();
			++warm;
		}
		double[] ms = new double[runs];
		long[] alloc = new long[runs];
		long id = Thread.currentThread().getId();
		for (int i = 0; i < runs; ++i) {
			long a = allocated(id);
			long t = System.nanoTime();
			Object o = work.get();
			ms[i] = (System.nanoTime() - t) / 1e6;
			alloc[i] = allocated(id) - a;
			sink = o;
		}
		Arrays.sort(ms);
		Arrays.sort(alloc);
		return new Stats(ms[runs / 2], ms[(int) (runs * 0.9)],
				ms[runs - 1], ms[0], alloc[runs / 2], runs);
	}

	/**
	 * Time many small operations: op is called count times per run and
	 * the time per call is reported.
	 */
	static Stats timeEach(Runnable op, int count, long warmMillis,
			int runs) {
		Stats s = time(() -> {
			for (int i = 0; i < count; ++i) {
				op.run();
			}
			return null;
		}, warmMillis, runs);
		return new Stats(s.medianMs / count, s.p90Ms / count,
				s.maxMs / count, s.minMs / count, s.allocBytes / count,
				runs);
	}

	/**
	 * Bytes that copies results of make keep alive, per result, by
	 * the heap in use after collections.
	 */
	static long retained(Supplier<Object> make, int copies) {
		Object[] keep = new Object[copies];
		long best = Long.MAX_VALUE;
		for (int round = 0; round < 3; ++round) {
			Arrays.fill(keep, null);
			long before = used();
			for (int i = 0; i < copies; ++i) {
				keep[i] = make.get();
			}
			long after = used();
			best = Math.min(best, (after - before) / copies);
		}
		sink = keep;
		return best;
	}

	static long used() {
		Runtime r = Runtime.getRuntime();
		for (int i = 0; i < 4; ++i) {
			System.gc();
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return r.totalMemory() - r.freeMemory();
	}

	private static String load() {
		try {
			Object os = bean("getOperatingSystemMXBean");
			return String.format(java.util.Locale.ROOT, "%.1f",
					(Double) Class.forName(
							"java.lang.management.OperatingSystemMXBean")
							.getMethod("getSystemLoadAverage").invoke(os));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "unknown";
		}
	}

	static String machine() {
		Runtime r = Runtime.getRuntime();
		return System.getProperty("os.name") + " " +
				System.getProperty("os.arch") + ", " +
				r.availableProcessors() + " cores, Java " +
				System.getProperty("java.version") + " (" +
				System.getProperty("java.vm.name") + "), max heap " +
				r.maxMemory() / 1048576 + " MB, load average " +
				load();
	}
}
