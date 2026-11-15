package de.markusfisch.android.shadereditor.fragment;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.syntax.GlslDocs;
import de.markusfisch.android.shadereditor.syntax.GlslWords;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.DocsSheet;

/**
 * Explains a word in a sheet: a built-in word of GLSL ES from the docs
 * asset, which is read on the first request, off the main thread, or
 * one of the app's uniforms with its text of Add uniform, and Add
 * uniform while the file does not declare it. Nothing happens per
 * keystroke; a word is looked up only on request.
 */
final class WordDocs implements CodeView.LookUp {
	interface Host {
		/** The declarations of the file, possibly of an older text. */
		@NonNull
		Outline declared();

		/** Add statement as Add uniform does. */
		void addUniform(@NonNull String statement);
	}

	private static final String ASSET = "glsl-docs.txt";
	private static final Map<String, UniformDef> UNIFORMS = new HashMap<>();
	private static final Executor READER = Executors.newSingleThreadExecutor();
	// Read once, on the main thread's word, for the process.
	@Nullable
	private static GlslDocs docs;

	static {
		for (UniformDef uniform : UniformTable.BUILTINS) {
			UNIFORMS.put(uniform.getName(), uniform);
		}
	}

	private final View view;
	private final Host host;
	private final Handler handler = new Handler(Looper.getMainLooper());

	WordDocs(@NonNull View view, @NonNull Host host) {
		this.view = view;
		this.host = host;
	}

	/** The statement Add uniform adds for uniform. */
	@NonNull
	static String statement(@NonNull UniformDef uniform) {
		return "uniform " + uniform.getType().getKeyword() + " " +
				uniform.getDeclarator() + ";";
	}

	/** The app's uniform called name, or null. */
	@Nullable
	static UniformDef uniform(@NonNull String name) {
		return UNIFORMS.get(name);
	}

	@Override
	public boolean has(@NonNull String word) {
		return UNIFORMS.containsKey(word) ||
				GlslWords.style(word, 0, word.length()) != Style.IDENTIFIER;
	}

	@Override
	public void lookUp(@NonNull String word) {
		UniformDef uniform = UNIFORMS.get(word);
		if (uniform != null) {
			showUniform(uniform);
		} else if (docs != null) {
			showBuiltin(word);
		} else {
			Context app = view.getContext().getApplicationContext();
			READER.execute(() -> {
				GlslDocs read = read(app);
				handler.post(() -> {
					if (read != null) {
						docs = read;
						if (view.isAttachedToWindow()) {
							showBuiltin(word);
						}
					}
				});
			});
		}
	}

	@Nullable
	private static GlslDocs read(@NonNull Context context) {
		try (Reader reader = new InputStreamReader(
				context.getAssets().open(ASSET), StandardCharsets.UTF_8)) {
			return GlslDocs.read(reader);
		} catch (IOException e) {
			// Not shipped in this build, or broken, which a test catches.
			Log.e("WordDocs", "Cannot read " + ASSET, e);
			return null;
		}
	}

	private void showBuiltin(@NonNull String word) {
		GlslDocs.Entry entry = docs.lookup(word);
		if (entry == null) {
			return;
		}
		Context context = view.getContext();
		String kind = context.getString(R.string.docs_kind_versions,
				context.getString(kindName(entry.kind())),
				context.getString(entry.es100()
						? entry.es300()
						? R.string.docs_es_both
						: R.string.docs_es_100
						: R.string.docs_es_300));
		show(new DocsSheet.Content(word, kind, entry.text(),
				lines(entry.forms()), spoken(context, entry), entry.detail(),
				entry.note(), lines(entry.example())), null, null);
	}

	private void showUniform(@NonNull UniformDef uniform) {
		Context context = view.getContext();
		String name = uniform.getName();
		String statement = statement(uniform);
		boolean declared = false;
		for (Outline.Declaration declaration :
				host.declared().declarations()) {
			declared |= declaration.name().equals(name);
		}
		show(new DocsSheet.Content(name,
						context.getString(R.string.docs_kind_versions,
								context.getString(R.string.docs_builtin_uniform),
								uniform.getType().getKeyword()),
						context.getString(uniform.getDescription()),
						statement, null, null,
						declared ? null : context.getString(
								R.string.docs_not_declared), null),
				declared ? null : context.getString(R.string.docs_add_uniform),
				() -> host.addUniform(statement));
	}

	/**
	 * What TalkBack says for the forms of a function: the names of its
	 * parameters, as "smoothstep takes edge0, edge1 and x", once for
	 * each list of names; null for anything else.
	 */
	@Nullable
	static String spoken(@NonNull Context context,
			@NonNull GlslDocs.Entry entry) {
		if (entry.kind() != GlslDocs.Kind.FUNCTION) {
			return null;
		}
		List<String> said = new ArrayList<>();
		for (String form : entry.forms()) {
			List<String> names = GlslDocs.parameterNames(form);
			String sentence = names.isEmpty()
					? context.getString(R.string.a11y_takes_nothing,
					entry.name())
					: context.getString(R.string.a11y_takes, entry.name(),
					list(context, names));
			if (!said.contains(sentence)) {
				said.add(sentence);
			}
		}
		return TextUtils.join(". ", said);
	}

	// Returns "a, b and c".
	@NonNull
	private static String list(@NonNull Context context,
			@NonNull List<String> names) {
		int last = names.size() - 1;
		return last == 0
				? names.get(0)
				: context.getString(R.string.a11y_and,
				TextUtils.join(", ", names.subList(0, last)),
				names.get(last));
	}

	// Opens the sheet; when it closes, the code view has the focus
	// again, and the keyboard, which the sheet sends down, comes back
	// if it was up.
	private void show(@NonNull DocsSheet.Content content,
			@Nullable String action, @Nullable Runnable onAction) {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
		boolean keyboard = insets != null &&
				insets.isVisible(WindowInsetsCompat.Type.ime());
		DocsSheet sheet = new DocsSheet(view.getContext(), content, action,
				onAction);
		sheet.setOnDismiss(() -> view.post(() -> refocus(keyboard)));
		sheet.show();
	}

	private void refocus(boolean keyboard) {
		if (!view.isAttachedToWindow() || !view.requestFocus() || !keyboard) {
			return;
		}
		InputMethodManager imm = (InputMethodManager) view.getContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.showSoftInput(view, 0);
		}
	}

	@Nullable
	private static String lines(@NonNull List<String> lines) {
		return lines.isEmpty() ? null : TextUtils.join("\n", lines);
	}

	private static int kindName(@NonNull GlslDocs.Kind kind) {
		return switch (kind) {
			case FUNCTION -> R.string.docs_builtin_function;
			case TYPE -> R.string.docs_builtin_type;
			case QUALIFIER -> R.string.docs_qualifier;
			case KEYWORD -> R.string.docs_keyword;
			case VARIABLE -> R.string.docs_builtin_variable;
			case CONSTANT -> R.string.docs_builtin_constant;
		};
	}
}
