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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.syntax.GlslDocs;
import de.markusfisch.android.shadereditor.syntax.GlslWords;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.DocsSheet;

/**
 * Explains a word in a sheet: a built-in word of GLSL ES from the docs
 * asset, which is read on the first request, off the main thread; one
 * of the app's uniforms with its text of Add uniform, and Add uniform
 * while the file does not declare it; or what the file or a file it
 * includes declares, with its declaration, the comment that belongs to
 * it and the way to it. Nothing happens per
 * keystroke; a word is looked up only on request. The same certain fix
 * goes into the row of a line where the compiler quotes one of the
 * app's uniforms as undeclared and the file does not declare it.
 */
final class WordDocs implements CodeView.LookUp, CodeView.RowFix {
	interface Host {
		/** What is known of the file, possibly of an older text. */
		@NonNull
		Knowledge declared();

		/** What the file sees of other files. */
		@NonNull
		Knowledge.Scope scope();

		/** Where the caret is, for what a word means there. */
		int offset();

		/** The line, from 0, of an offset of the file's text. */
		int lineOf(int offset);

		/** Put the caret in line, from 0. */
		void goToLine(int line);

		/** Show file, a path of the project, at line, from 0. */
		void open(@NonNull String file, int line);

		/** Add statement as Add uniform does. */
		void addUniform(@NonNull String statement);
	}

	private static final String ASSET = "glsl-docs.txt";
	// A name in single quotes, as compilers quote the undeclared one.
	private static final Pattern QUOTED = Pattern.compile(
			"'([A-Za-z_][A-Za-z0-9_]*)'");
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

	/**
	 * The app's uniform a message of messages says is undeclared, while
	 * the declarations do not have its name, or null.
	 */
	@Nullable
	static UniformDef undeclaredUniform(@NonNull List<String> messages,
			@NonNull Knowledge declarations) {
		for (String message : messages) {
			if (!message.toLowerCase(Locale.ROOT)
					.contains("undeclared")) {
				continue;
			}
			Matcher m = QUOTED.matcher(message);
			while (m.find()) {
				UniformDef uniform = UNIFORMS.get(m.group(1));
				if (uniform != null && declarations.at(-1,
						uniform.getName()).isEmpty()) {
					return uniform;
				}
			}
		}
		return null;
	}

	@Nullable
	@Override
	public String label(@NonNull List<String> messages) {
		UniformDef uniform = undeclaredUniform(messages, host.declared());
		return uniform != null
				? view.getContext().getString(R.string.fix_add_uniform,
				uniform.getType().getKeyword(), uniform.getDeclarator())
				: null;
	}

	@Override
	public void apply(@NonNull List<String> messages) {
		UniformDef uniform = undeclaredUniform(messages, host.declared());
		if (uniform != null) {
			host.addUniform(statement(uniform));
		}
	}

	@Override
	public boolean has(@NonNull String word) {
		return UNIFORMS.containsKey(word) ||
				GlslWords.style(word, 0, word.length()) != Style.IDENTIFIER ||
				!declared(word).isEmpty();
	}

	// What word is declared as, as seen from the caret: in the file,
	// else in the files around it.
	@NonNull
	private List<Knowledge.Symbol> declared(@NonNull String word) {
		List<Knowledge.Symbol> found = host.declared().at(host.offset(),
				word);
		return found.isEmpty() ? host.scope().find(word) : found;
	}

	@Override
	public void lookUp(@NonNull String word) {
		UniformDef uniform = UNIFORMS.get(word);
		if (uniform != null) {
			showUniform(uniform);
		} else if (GlslWords.style(word, 0, word.length()) ==
				Style.IDENTIFIER) {
			showDeclared(declared(word));
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
				entry.note(), lines(entry.example())), null, null, false);
	}

	private void showUniform(@NonNull UniformDef uniform) {
		Context context = view.getContext();
		String name = uniform.getName();
		String statement = statement(uniform);
		boolean declared = !declared(name).isEmpty();
		show(new DocsSheet.Content(name,
						context.getString(R.string.docs_kind_versions,
								context.getString(R.string.docs_builtin_uniform),
								uniform.getType().getKeyword()),
						context.getString(uniform.getDescription()),
						statement, null, null,
						declared ? null : context.getString(
								R.string.docs_not_declared), null),
				declared ? null : context.getString(R.string.docs_add_uniform),
				() -> host.addUniform(statement), false);
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
			@Nullable String action, @Nullable Runnable onAction,
			boolean leads) {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
		boolean keyboard = insets != null &&
				insets.isVisible(WindowInsetsCompat.Type.ime());
		DocsSheet sheet = new DocsSheet(view.getContext(), content, action,
				onAction, leads);
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

	// What the file or a file around it declares: the declaration, the
	// comment that belongs to it, and the way to it.
	private void showDeclared(@NonNull List<Knowledge.Symbol> forms) {
		if (forms.isEmpty()) {
			return;
		}
		Context context = view.getContext();
		Knowledge.Symbol first = forms.get(0);
		String file = first.file();
		int line = file != null
				? host.scope().line(first)
				: host.lineOf(first.start());
		String where = line < 0 ? file : file != null
				? context.getString(R.string.docs_where, file, line + 1)
				: context.getString(R.string.docs_where_line, line + 1);
		String kind = context.getString(kindName(first.kind()));
		String action = line < 0 ? null : file != null
				? context.getString(R.string.docs_open, file)
				: context.getString(R.string.docs_go_to_line, line + 1);
		show(new DocsSheet.Content(first.name(), where == null ? kind
						: context.getString(R.string.docs_kind_versions, kind,
								where), documentation(forms), code(forms),
						null, null, null, null), action,
				file != null ? () -> host.open(file, line)
						: () -> host.goToLine(line), true);
	}

	/**
	 * How forms are written, each in a line: the forms of a function,
	 * or one declaration; a struct with its fields.
	 */
	@NonNull
	static String code(@NonNull List<Knowledge.Symbol> forms) {
		StringBuilder sb = new StringBuilder();
		for (Knowledge.Symbol form : forms) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(form.detail());
			if (form.kind() == Knowledge.Kind.TYPE) {
				sb.append(" {");
				for (Knowledge.Symbol field : form.parts()) {
					sb.append("\n  ").append(field.detail()).append(';');
				}
				sb.append("\n}");
			}
		}
		return sb.toString();
	}

	// The comment of the first form that has one.
	@Nullable
	private static String documentation(
			@NonNull List<Knowledge.Symbol> forms) {
		for (Knowledge.Symbol form : forms) {
			if (form.documentation() != null) {
				return form.documentation();
			}
		}
		return null;
	}

	private static int kindName(@NonNull Knowledge.Kind kind) {
		return switch (kind) {
			case FUNCTION -> R.string.docs_function;
			case UNIFORM -> R.string.docs_uniform;
			case MACRO -> R.string.docs_macro;
			case TYPE -> R.string.docs_struct;
			default -> R.string.docs_variable;
		};
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
