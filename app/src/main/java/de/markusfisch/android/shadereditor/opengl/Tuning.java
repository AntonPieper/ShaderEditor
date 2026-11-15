package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

/**
 * One uniform of the running shader that the host sets: while a number
 * of the text is tuned, a build has the number replaced by this name,
 * and each new value is set with the next frame instead of building
 * again. Every program whose text uses the name declares it; the others
 * do not see it.
 *
 * The value may be set on any thread. The GL thread writes it into the
 * programs only when the host asks for it (ShaderEngine.applyTuning()),
 * so a frame without a change costs nothing for it.
 */
public final class Tuning {
	/** A scalar: one number literal is tuned at a time. */
	public enum Type {
		FLOAT,
		INT,
		// A literal with a u suffix; only in GLSL ES 3.00.
		UINT;

		@NonNull
		String glsl() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** Told on the GL thread what became of the build that uses it. */
	public interface Listener {
		/** The build draws now; values set from now on show. */
		void onLive();

		/**
		 * The build did not compile or link, for example because the
		 * text needs a constant where the number is. The build before
		 * keeps drawing; nothing was reported to the engine's listener.
		 */
		void onRefused(@NonNull List<ProjectDiagnostic> errors);
	}

	/**
	 * Macros the engine reads from the text itself: a number in one is
	 * not compiled, so it cannot become a uniform.
	 */
	public static final Set<String> HOST_MACROS = Set.of(
			PlanSource.FTIME_PERIOD);

	@NonNull
	final String name;
	@NonNull
	final Type type;
	@NonNull
	private final Listener listener;
	private volatile double value;

	/**
	 * name is the uniform's name, unused by the text; value is the
	 * number's own value, so that the first frame shows what the text
	 * shows. Integers are kept exactly: a double holds every int and
	 * every uint. An int or uint outside its range is written with the
	 * language's 32-bit wrap.
	 */
	public Tuning(@NonNull String name, @NonNull Type type, double value,
			@NonNull Listener listener) {
		this.name = name;
		this.type = type;
		this.value = value;
		this.listener = listener;
	}

	@NonNull
	public String name() {
		return name;
	}

	@NonNull
	public Type type() {
		return type;
	}

	/** Any thread. The value the next applyTuning() writes. */
	public void set(double value) {
		this.value = value;
	}

	double value() {
		return value;
	}

	void live() {
		listener.onLive();
	}

	void refused(@NonNull List<ProjectDiagnostic> errors) {
		listener.onRefused(errors);
	}

	// The lines that declare it, highp where the fragment shader has it,
	// as the template does: the same in GLSL ES 1.00 and 3.00, where
	// GL_FRAGMENT_PRECISION_HIGH is always defined.
	@NonNull
	List<String> declaration() {
		String declared = type.glsl() + " " + name + ";";
		return List.of(
				"#ifdef GL_FRAGMENT_PRECISION_HIGH",
				"uniform highp " + declared,
				"#else",
				"uniform mediump " + declared,
				"#endif");
	}
}
