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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Definitions;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.editor.WordPlaces;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.ProjectParseResult;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.syntax.GlslCompletion;
import de.markusfisch.android.shadereditor.syntax.GlslDocs;
import de.markusfisch.android.shadereditor.syntax.GlslWords;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.DocsSheet;
import de.markusfisch.android.shadereditor.widget.NameView;
import de.markusfisch.android.shadereditor.widget.SignatureView;
import de.markusfisch.android.shadereditor.widget.UsesSheet;

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

		/** The revision of the file's text, which an edit changes. */
		int revision();

		/** The path of the file shown, or "" outside a folder. */
		@NonNull
		String file();

		/**
		 * Read the texts of the files the file shown may see, as Uses
		 * reads them, the file shown's under file(); done gets them by
		 * path, or null when not all could be read.
		 */
		void texts(@NonNull FileTexts.Callback<Map<String, Text>> done);

		/**
		 * Go to the name word at start of file, a path, or the file
		 * shown, in line, from 0, with the way back.
		 */
		void goTo(@NonNull String file, int start, int line,
				@NonNull String word);

		/**
		 * The types of the arguments of the call of word whose name
		 * the caret is in, null for one not told; null when the caret
		 * is in no call of word.
		 */
		@Nullable
		List<String> arguments(@NonNull String word);

		/** The docs are read: what waited for them can be asked again. */
		void onDocsRead();

		/**
		 * Add statements as Add uniform does, in this order, as one
		 * undo step.
		 */
		void addUniforms(@NonNull List<String> statements);

		/** Whether Uses can list the places of a name. */
		boolean offersUses();

		/**
		 * List the places of word, declared in file, a path of the
		 * project, or in this one for null; keyboard tells whether the
		 * keyboard comes back after, renamable whether Rename is
		 * offered: not for one of the app's uniforms, which the app
		 * sets by its name; rename whether the sheet opens renaming.
		 */
		void uses(@NonNull String word, @Nullable String file,
				boolean keyboard, boolean renamable, boolean rename);
	}

	private static final String ASSET = "glsl-docs.txt";
	// A name in single quotes, as compilers quote the undeclared one.
	private static final Pattern QUOTED = Pattern.compile(
			"'([A-Za-z_][A-Za-z0-9_]*)'");
	private static final Map<String, UniformDef> UNIFORMS = new HashMap<>();
	private static final Executor READER = Executors.newSingleThreadExecutor();
	private static final Executor FINDER = Executors.newSingleThreadExecutor();
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
	private boolean reading;
	// Whether a read of the texts in reach is on its way, and the one
	// asked for meanwhile, to run after it.
	private boolean finding;
	@Nullable
	private Runnable waiting;
	// Whether the sheet shown last handed the keyboard on to the next.
	private boolean handedOn;
	// The word to explain once the docs are read.
	@Nullable
	private String asked;

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
	 * The app's uniforms messages say are undeclared, while the
	 * declarations do not have their names, each once, in the order the
	 * messages name them.
	 */
	@NonNull
	static List<UniformDef> undeclaredUniforms(@NonNull List<String> messages,
			@NonNull Knowledge declarations) {
		List<UniformDef> found = new ArrayList<>();
		for (String message : messages) {
			if (!message.toLowerCase(Locale.ROOT)
					.contains("undeclared")) {
				continue;
			}
			Matcher m = QUOTED.matcher(message);
			while (m.find()) {
				UniformDef uniform = UNIFORMS.get(m.group(1));
				if (uniform != null && !found.contains(uniform) &&
						declarations.at(-1, uniform.getName()).isEmpty()) {
					found.add(uniform);
				}
			}
		}
		return found;
	}

	/**
	 * items joined by ", " but the last, which and joins, a format of
	 * two strings such as "%1$s and %2$s".
	 */
	@NonNull
	static String joinAnd(@NonNull List<String> items, @NonNull String and) {
		int last = items.size() - 1;
		if (last < 0) {
			return "";
		}
		StringBuilder head = new StringBuilder(items.get(0));
		for (int i = 1; i < last; ++i) {
			head.append(", ").append(items.get(i));
		}
		return last == 0
				? head.toString()
				: String.format(and, head, items.get(last));
	}

	// One fix adds all of the app's uniforms a line misses: "Add uniform
	// float time", shorter where it does not fit; "Add 2 uniforms" for
	// more than one.
	@NonNull
	@Override
	public List<CodeView.Fix> fixes(int line,
			@NonNull List<String> messages) {
		List<UniformDef> uniforms = undeclaredUniforms(messages,
				host.declared());
		if (uniforms.isEmpty()) {
			return List.of();
		}
		Context context = view.getContext();
		if (uniforms.size() == 1) {
			UniformDef uniform = uniforms.get(0);
			String full = context.getString(R.string.fix_add_uniform,
					uniform.getType().getKeyword(), uniform.getDeclarator());
			return List.of(CodeView.Fix.of(full, List.of(full,
					context.getString(R.string.fix_add_uniform_name,
							uniform.getName()),
					context.getString(R.string.fix_add_uniform_short))));
		}
		List<String> pairs = new ArrayList<>();
		for (UniformDef uniform : uniforms) {
			pairs.add(uniform.getType().getKeyword() + " " +
					uniform.getDeclarator());
		}
		return List.of(CodeView.Fix.of(context.getString(
						R.string.a11y_fix_add_uniforms, joinAnd(pairs,
								context.getString(R.string.a11y_list_and))),
				List.of(context.getResources().getQuantityString(
						R.plurals.fix_add_uniforms, uniforms.size(),
						uniforms.size()))));
	}

	@Override
	public void apply(int line, @NonNull List<String> messages, int index) {
		List<String> statements = new ArrayList<>();
		for (UniformDef uniform : undeclaredUniforms(messages,
				host.declared())) {
			statements.add(statement(uniform));
		}
		if (!statements.isEmpty()) {
			host.addUniforms(statements);
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

	/**
	 * Whether word at offset means a local or a parameter: something
	 * else than the name alone means in the file. Such a word is what
	 * its function declares, also when it is named like one of the
	 * app's uniforms.
	 */
	static boolean isLocal(@NonNull Knowledge known, int offset,
			@NonNull String word) {
		List<Knowledge.Symbol> here = known.at(offset, word);
		return !here.isEmpty() && !here.equals(known.at(-1, word));
	}

	@Override
	public void lookUp(@NonNull String word) {
		UniformDef uniform = UNIFORMS.get(word);
		List<Knowledge.Symbol> declared = declared(word);
		if (uniform != null && !isLocal(host.declared(), host.offset(),
				word)) {
			showUniform(uniform);
		} else if (!declared.isEmpty()) {
			showDeclared(declared);
		} else if (GlslWords.style(word, 0, word.length()) ==
				Style.IDENTIFIER) {
			// Declared nowhere the editor knows: where it is written;
			// that there is none only when nothing in reach writes it.
			definitions(word, found -> show(new DocsSheet.Content(word,
					view.getContext().getString(found.answer().form() ==
							Definitions.Form.NONE ? R.string.no_definition
							: R.string.docs_name), null, null, null, null,
					null, null), null, null, word, false, null));
		} else if (docs != null) {
			showBuiltin(word);
		} else {
			readDocs(word);
		}
	}

	// Reads the docs off the main thread, once, and then explains
	// word, if any.
	private void readDocs(@Nullable String word) {
		if (word != null) {
			asked = word;
		}
		if (reading) {
			return;
		}
		reading = true;
		Context app = view.getContext().getApplicationContext();
		READER.execute(() -> {
			GlslDocs read = read(app);
			handler.post(() -> {
				reading = false;
				String explain = asked;
				asked = null;
				if (read != null) {
					docs = read;
					if (explain != null && view.isAttachedToWindow()) {
						showBuiltin(explain);
					}
					host.onDocsRead();
				}
			});
		});
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
				entry.note(), lines(entry.example())), null, null, null,
				false, null);
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
				() -> host.addUniforms(List.of(statement)),
				declared ? name : null, true, null);
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

	// Opens the sheet; for name, declared in file or in this one for
	// null, with Definition where it is declared, and Uses and Rename
	// where the folder lists uses, Rename not for one of the app's
	// uniforms. When it closes, the code view has the focus again, and
	// the keyboard, which the sheet sends down, comes back if it was
	// up, unless a sheet follows, which does that when it goes.
	private void show(@NonNull DocsSheet.Content content,
			@Nullable String action, @Nullable Runnable onAction,
			@Nullable String name, boolean declared, @Nullable String file) {
		boolean keyboard = keyboardUp();
		DocsSheet sheet = new DocsSheet(view.getContext(), content, action,
				onAction);
		handedOn = false;
		Runnable uses = null;
		Runnable rename = null;
		if (name != null && listsUses(name)) {
			boolean renamable = renames(name);
			uses = () -> {
				handedOn = true;
				host.uses(name, file, keyboard, renamable, false);
			};
			rename = !renamable ? null : () -> {
				handedOn = true;
				host.uses(name, file, keyboard, true, true);
			};
		}
		sheet.setActions(name != null && declared
				? () -> {
					handedOn = true;
					definition(name, keyboard);
				}
				: null, uses, rename);
		sheet.setOnDismiss(() -> {
			if (!handedOn) {
				view.post(() -> refocus(keyboard));
			}
		});
		sheet.show();
	}

	void refocus(boolean keyboard) {
		if (!view.isAttachedToWindow() || !view.requestFocus() || !keyboard) {
			return;
		}
		InputMethodManager imm = (InputMethodManager) view.getContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.showSoftInput(view, 0);
		}
	}

	// The names of the parameters of the last function asked for, form
	// by form, so that typing its arguments reads them once.
	@Nullable
	private String formsOf;
	@Nullable
	private Knowledge formsKnown;
	@NonNull
	private List<Form> forms = new ArrayList<>();

	/** A form of a function: its parameters' names and their types. */
	record Form(@NonNull List<String> names, @NonNull List<String> types) {
	}

	/**
	 * The call of name the strip shows while the caret is in argument,
	 * from 0: the function with the names of its parameters, or null
	 * for a name that is no function with parameters. An own function
	 * or macro is what the file or a file around it declares; a
	 * built-in comes from the docs, which are read at the first call
	 * and answer from the next key on.
	 */
	@Nullable
	SignatureView.Signature signature(@NonNull String name, int argument,
			@NonNull List<String> types) {
		// What the file declares may have changed with its text.
		Knowledge known = host.declared();
		if (!name.equals(formsOf) || known != formsKnown) {
			List<Form> read = forms(name);
			if (read == null) {
				return null;
			}
			formsOf = name;
			formsKnown = known;
			forms = read;
		}
		List<String> names = form(forms, argument, types);
		return names == null ? null
				: new SignatureView.Signature(name, names, argument);
	}

	/**
	 * The form the strip shows for the caret in argument, the arguments
	 * before it having these types as far as they can be told: the
	 * first that has that many parameters and takes them; else the
	 * first that has that many, else the one with the most. Null when
	 * there is none.
	 */
	@Nullable
	static List<String> form(@NonNull List<Form> forms, int argument,
			@NonNull List<String> types) {
		List<String> first = null;
		List<String> longest = null;
		for (Form form : forms) {
			List<String> names = form.names();
			if (names.size() > argument) {
				if (GlslCompletion.takes(form.types(), types)) {
					return names;
				} else if (first == null) {
					first = names;
				}
			} else if (longest == null || names.size() > longest.size()) {
				longest = names;
			}
		}
		return first != null ? first : longest;
	}

	// The parameter names of every form of name; null while the docs
	// are still being read.
	@Nullable
	private List<Form> forms(@NonNull String name) {
		List<Form> found = new ArrayList<>();
		if (GlslWords.style(name, 0, name.length()) == Style.IDENTIFIER) {
			for (Knowledge.Symbol form : declared(name)) {
				List<String> names = names(form);
				List<String> types = new ArrayList<>();
				for (Knowledge.Symbol part : form.parts()) {
					types.add(part.type());
				}
				if (!names.isEmpty()) {
					found.add(new Form(names, types));
				}
			}
			return found;
		}
		if (docs == null) {
			readDocs(null);
			return null;
		}
		GlslDocs.Entry entry = docs.lookup(name);
		if (entry != null && entry.kind() == GlslDocs.Kind.FUNCTION) {
			for (String form : entry.forms()) {
				List<String> names = GlslDocs.parameterNames(form);
				if (!names.isEmpty()) {
					found.add(new Form(names,
							GlslDocs.parameterTypes(form)));
				}
			}
		}
		return found;
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
		show(new DocsSheet.Content(first.name(), where == null ? kind
						: context.getString(R.string.docs_kind_versions, kind,
								where), documentation(forms), code(forms),
						null, null, null, null), null, null, first.name(),
				true, file);
	}

	/**
	 * What the strip shows for word, a name the caret rests in, see
	 * {@link NameView.Name}: how it is declared, as far as the editor
	 * knows at once, and the one thing to do with it. For a built-in or
	 * an app uniform the file lacks that is nothing; else the action
	 * waits for where the name may be declared, which later gets:
	 * Definition, or Uses on its own declaration and where it has no
	 * place. A tap on the form looks it up. Null while the docs a
	 * built-in needs are read.
	 */
	@Nullable
	NameView.Name name(@NonNull String word,
			@NonNull FileTexts.Callback<NameView.Name> later) {
		Context context = view.getContext();
		Runnable open = () -> lookUp(word);
		List<Knowledge.Symbol> declared = declared(word);
		// A built-in, unless the file declares a macro of its name.
		if (GlslWords.style(word, 0, word.length()) != Style.IDENTIFIER &&
				declared.isEmpty()) {
			GlslDocs.Entry entry = docs != null ? docs.lookup(word) : null;
			if (docs == null) {
				readDocs(null);
			}
			if (entry == null || entry.forms().isEmpty()) {
				return null;
			}
			String form = entry.forms().get(0);
			if (entry.kind() == GlslDocs.Kind.FUNCTION) {
				form = call(word, GlslDocs.parameterNames(form));
			}
			String spoken = spoken(context, entry);
			return new NameView.Name(word, form, null,
					spoken != null ? spoken : form, open, null, 0, null,
					null, false);
		}
		UniformDef uniform = UNIFORMS.get(word);
		if (declared.isEmpty() && uniform != null) {
			String form = uniform.getType().getKeyword() + " " +
					uniform.getDeclarator();
			return new NameView.Name(word, form, null, form, open, null, 0,
					null, null, false);
		}
		Knowledge.Symbol first = declared.isEmpty() ? null : declared.get(0);
		String form = first == null ? word : first.kind() ==
				Knowledge.Kind.FUNCTION ? call(word, names(first))
				: first.detail();
		// A function as TalkBack says a built-in one: "disc takes p and r".
		List<String> parameters = first != null &&
				first.kind() == Knowledge.Kind.FUNCTION ? names(first) : null;
		String spoken = parameters == null ? form : parameters.isEmpty()
				? context.getString(R.string.a11y_takes_nothing, word)
				: context.getString(R.string.a11y_takes, word,
				list(context, parameters));
		definitions(word, found -> later.done(named(word, form, spoken,
				open, found)));
		return new NameView.Name(word, form, null, spoken, open, null, 0,
				null, null, true);
	}

	// The name with what found says: its note and its action.
	@NonNull
	private NameView.Name named(@NonNull String word, @NonNull String form,
			@NonNull String spoken, @NonNull Runnable open,
			@NonNull Found found) {
		Context context = view.getContext();
		Definitions.Answer answer = found.answer();
		List<WordPlaces.Place> rows = found.rows();
		String note = null;
		if (answer.form() == Definitions.Form.NONE) {
			note = context.getString(R.string.no_definition);
		} else if (rows.size() > 1) {
			boolean forms = true;
			for (Definitions.Place place : answer.places()) {
				forms &= place.types() != null;
			}
			note = context.getResources().getQuantityString(forms
					? R.plurals.name_forms
					: R.plurals.name_definitions, rows.size(), rows.size());
		}
		String said = spoken + (note != null ? ", " + note : "");
		if (rows.isEmpty() || found.here()) {
			if (!listsUses(word)) {
				return new NameView.Name(word, form, note, said, open,
						null, 0, null, null, false);
			}
			return new NameView.Name(word, form, note, said, open,
					context.getString(R.string.uses), R.drawable.ic_list,
					context.getString(R.string.a11y_uses_of, word),
					() -> uses(word, false), false);
		}
		WordPlaces.Place first = rows.get(0);
		String where = answer.form() != Definitions.Form.JUMP
				? context.getResources().getQuantityString(
				R.plurals.a11y_definitions, rows.size(), word, rows.size())
				: first.file().equals(host.file())
				? context.getString(R.string.a11y_definition, word,
				first.line() + 1)
				: context.getString(R.string.a11y_definition_in, word,
				first.file(), first.line() + 1);
		return new NameView.Name(word, form, note, said, open,
				context.getString(R.string.definition),
				R.drawable.ic_arrow_forward, where,
				() -> definition(word, keyboardUp()), false);
	}

	// "name(a, b)".
	@NonNull
	private static String call(@NonNull String name,
			@NonNull List<String> parameters) {
		return name + "(" + TextUtils.join(", ", parameters) + ")";
	}

	// The names of the parameters of function, of one without a name
	// its type.
	@NonNull
	private static List<String> names(@NonNull Knowledge.Symbol function) {
		List<String> names = new ArrayList<>();
		for (Knowledge.Symbol part : function.parts()) {
			names.add(part.name().isEmpty() && part.type() != null
					? part.type() : part.name());
		}
		return names;
	}

	/**
	 * Where word may be declared, its places as rows of a list, and
	 * whether the caret is on one of its declarations.
	 */
	record Found(@NonNull Definitions.Answer answer,
			@NonNull List<WordPlaces.Place> rows, boolean here) {
	}

	/**
	 * List the uses of word at the caret, renaming at once when rename
	 * and word can be renamed: not for a built-in, and only where the
	 * folder lists uses.
	 */
	void uses(@NonNull String word, boolean rename) {
		if (!listsUses(word)) {
			return;
		}
		boolean renamable = renames(word);
		host.uses(word, null, keyboardUp(), renamable, rename && renamable);
	}

	/**
	 * Return whether the uses of word at the caret can be listed: a name
	 * of the file or the files around, or declared nowhere, in a folder.
	 */
	boolean listsUses(@NonNull String word) {
		return host.offersUses() && (GlslWords.style(word, 0,
				word.length()) == Style.IDENTIFIER ||
				!declared(word).isEmpty());
	}

	/**
	 * Return whether word at the caret can be renamed where its uses are
	 * listed: not an app uniform, which the app sets by its name, unless
	 * a local or parameter of the name hides it.
	 */
	boolean renames(@NonNull String word) {
		return listsUses(word) && (!UNIFORMS.containsKey(word) ||
				isLocal(host.declared(), host.offset(), word));
	}

	/**
	 * Do what F12 does on word at the caret: go to its definition; on
	 * its own declaration list its uses; where it has no place, as for
	 * a built-in, an app uniform the file lacks or a name declared
	 * nowhere, explain it.
	 */
	void definitionKey(@NonNull String word) {
		boolean keyboard = keyboardUp();
		definitions(word, found -> {
			if (found.rows().isEmpty()) {
				lookUp(word);
			} else if (found.here()) {
				uses(word, false);
			} else {
				answer(word, found, keyboard);
			}
		});
	}

	/**
	 * Find where word, at the caret, may be declared, see
	 * {@link Definitions}, from the texts in reach read now, off the
	 * main thread; done is told on it, unless the text or the caret
	 * changed meanwhile.
	 */
	void definitions(@NonNull String word,
			@NonNull FileTexts.Callback<Found> done) {
		int offset = host.offset();
		int revision = host.revision();
		String shown = host.file();
		Runnable ask = () -> host.texts(texts -> FINDER.execute(() -> {
			Found found = texts == null
					? new Found(Definitions.Answer.UNKNOWN, List.of(), false)
					: find(texts, shown, offset, word);
			view.post(() -> {
				Runnable next = waiting;
				waiting = null;
				if (next != null) {
					next.run();
				} else {
					finding = false;
				}
				if (host.revision() == revision && host.offset() == offset) {
					done.done(found);
				}
			});
		}));
		// One read at a time; of those asked for meanwhile, the last.
		if (finding) {
			waiting = ask;
		} else {
			finding = true;
			ask.run();
		}
	}

	private static Found find(@NonNull Map<String, Text> texts,
			@NonNull String shown, int offset, @NonNull String word) {
		Map<String, String> strings = new LinkedHashMap<>();
		for (Map.Entry<String, Text> e : texts.entrySet()) {
			strings.put(e.getKey(), e.getValue().toString());
		}
		List<Definitions.Index> reach = Definitions.reach(strings,
				shown, (file, path) -> {
					ProjectParseResult<ProjectPath> from =
							ProjectPath.parse(file);
					ProjectParseResult<ProjectPath> to = from.isSuccess()
							? ProjectPath.resolve(from.value(), path) : null;
					return to != null && to.isSuccess()
							? to.value().toString() : null;
				});
		Definitions.Answer answer = Definitions.of(reach, offset, word);
		List<WordPlaces.Place> rows = new ArrayList<>();
		for (Definitions.Place place : answer.places()) {
			Text text = texts.get(place.file());
			int line = text.lineOf(place.start());
			int start = text.lineStart(line);
			rows.add(new WordPlaces.Place(place.file(), place.start(), line,
					place.start() - start, text.line(line), false));
		}
		return new Found(answer, rows, !reach.isEmpty() &&
				Definitions.declares(reach.get(0), offset, word));
	}

	/**
	 * Go where word, at the caret, is declared: there when it has one
	 * place; else a list of its places to choose from, after which the
	 * keyboard comes back if it was up, as it does after a jump or when
	 * nothing is found.
	 */
	void definition(@NonNull String word, boolean keyboard) {
		definitions(word, found -> answer(word, found, keyboard));
	}

	private void answer(@NonNull String word, @NonNull Found found,
			boolean keyboard) {
		if (found.answer().form() == Definitions.Form.LIST) {
			list(word, found, keyboard);
			return;
		} else if (found.answer().form() == Definitions.Form.JUMP) {
			goTo(found.rows().get(0), word);
		}
		refocus(keyboard);
	}

	private void goTo(@NonNull WordPlaces.Place row, @NonNull String word) {
		host.goTo(row.file(), row.start(), row.line(), word);
	}

	// Lists the places found in the rows of Uses, by file, the nearest
	// first and named; or the one function the call at the caret fits,
	// named, never chosen.
	private void list(@NonNull String word, @NonNull Found found,
			boolean keyboard) {
		Context context = view.getContext();
		String shown = host.file();
		Map<String, List<WordPlaces.Place>> byFile = new LinkedHashMap<>();
		for (WordPlaces.Place row : found.rows()) {
			List<WordPlaces.Place> inFile = byFile.get(row.file());
			if (inFile == null) {
				inFile = new ArrayList<>();
				byFile.put(row.file(), inFile);
			}
			inFile.add(row);
		}
		List<WordPlaces.Group> groups = new ArrayList<>();
		for (Map.Entry<String, List<WordPlaces.Place>> file :
				byFile.entrySet()) {
			groups.add(new WordPlaces.Group(WordPlaces.Group.Kind.FILE,
					file.getKey(), file.getValue()));
		}
		List<String> types = host.arguments(word);
		int fits = types != null && !types.contains(null)
				? Definitions.fitting(found.answer().places(), types)
				: -1;
		WordPlaces.Place nearest = found.rows().get(0);
		UsesSheet sheet = new UsesSheet(context,
				ShaderEditorApp.preferences.getFont(),
				row -> goTo(row, word));
		sheet.setOnDismiss(() -> view.post(() -> refocus(keyboard)));
		int count = found.rows().size();
		sheet.showList(new UsesSheet.Content(word, shown, groups, 0, false,
						false), context.getResources().getQuantityString(
						R.plurals.definitions, count, count),
				fits > -1 ? context.getString(R.string.definition_fits,
						host.lineOf(host.offset()) + 1,
						TextUtils.join(", ", types),
						found.rows().get(fits).line() + 1)
						: context.getString(R.string.definition_nearest,
						nearest.file().equals(shown)
								? context.getString(R.string.docs_where_line,
								nearest.line() + 1)
								: context.getString(R.string.docs_where,
								nearest.file(), nearest.line() + 1)));
	}

	// Whether the keyboard is up.
	private boolean keyboardUp() {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
		return insets != null &&
				insets.isVisible(WindowInsetsCompat.Type.ime());
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
