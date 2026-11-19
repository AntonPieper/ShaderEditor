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

		/** The file's text as it is now. */
		@NonNull
		Text text();

		/** The path of the file shown, or "" outside a folder. */
		@NonNull
		String file();

		/**
		 * Go where place is declared, in the file shown, or in the file
		 * it names, with the way back.
		 */
		void goTo(@NonNull Knowledge.Symbol place);

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
		Definitions.Found found = definitions(word);
		if (uniform != null && !isLocal(host.declared(), host.offset(),
				word)) {
			showUniform(uniform);
		} else if (!found.places().isEmpty()) {
			showDeclared(found.places());
		} else if (GlslWords.style(word, 0, word.length()) ==
				Style.IDENTIFIER) {
			// Declared nowhere the editor knows: where it is written;
			// that there is none only when that is sure.
			show(new DocsSheet.Content(word, view.getContext().getString(
					found.none() ? R.string.no_definition
							: R.string.docs_name), null, null, null, null,
					null, null), null, null, word, false, null);
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
				? () -> handedOn = definition(name, keyboard)
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
		int count = forms.size();
		String where = count > 1
				? context.getResources().getQuantityString(
				R.plurals.definitions, count, count)
				: line < 0 ? file : file != null
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
	 * {@link NameView.Name}: how it is declared, and the one thing to
	 * do with it: Definition; Uses on its own declaration and for a
	 * name declared nowhere; nothing for a built-in or an app uniform
	 * the file lacks. A tap on the form looks it up. Null while the
	 * docs a built-in needs are read.
	 */
	@Nullable
	NameView.Name name(@NonNull String word) {
		Context context = view.getContext();
		Runnable open = () -> lookUp(word);
		Definitions.Found found = definitions(word);
		List<Knowledge.Symbol> places = found.places();
		// A built-in, unless the file declares a macro of its name.
		if (GlslWords.style(word, 0, word.length()) != Style.IDENTIFIER &&
				places.isEmpty()) {
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
					null);
		}
		UniformDef uniform = UNIFORMS.get(word);
		if (places.isEmpty() && uniform != null) {
			String form = uniform.getType().getKeyword() + " " +
					uniform.getDeclarator();
			return new NameView.Name(word, form, null, form, open, null, 0,
					null, null);
		}
		Knowledge.Symbol first = places.isEmpty() ? null : places.get(0);
		String form = first == null ? word : first.kind() ==
				Knowledge.Kind.FUNCTION ? call(word, names(first))
				: first.detail();
		String note = null;
		if (first == null) {
			note = found.none() ? context.getString(R.string.no_definition)
					: null;
		} else if (places.size() > 1) {
			boolean forms = true;
			for (Knowledge.Symbol place : places) {
				forms &= place.kind() == Knowledge.Kind.FUNCTION;
			}
			note = context.getResources().getQuantityString(forms
					? R.plurals.name_forms
					: R.plurals.name_definitions, places.size(), places.size());
		}
		// A function as TalkBack says a built-in one: "disc takes p and r".
		List<String> parameters = first != null &&
				first.kind() == Knowledge.Kind.FUNCTION ? names(first) : null;
		String spoken = (parameters == null ? form : parameters.isEmpty()
				? context.getString(R.string.a11y_takes_nothing, word)
				: context.getString(R.string.a11y_takes, word,
				list(context, parameters))) + (note != null ? ", " + note : "");
		if (first == null || found.here()) {
			if (!listsUses(word)) {
				return new NameView.Name(word, form, note, spoken, open,
						null, 0, null, null);
			}
			return new NameView.Name(word, form, note, spoken, open,
					context.getString(R.string.uses), R.drawable.ic_list,
					context.getString(R.string.a11y_uses_of, word),
					() -> uses(word, false));
		}
		String where;
		if (found.only() == null) {
			where = context.getResources().getQuantityString(
					R.plurals.a11y_definitions, places.size(), word,
					places.size());
		} else if (first.file() != null) {
			where = context.getString(R.string.a11y_definition_in, word,
					first.file(), host.scope().line(first) + 1);
		} else {
			where = context.getString(R.string.a11y_definition, word,
					host.lineOf(first.start()) + 1);
		}
		return new NameView.Name(word, form, note, spoken, open,
				context.getString(R.string.definition),
				R.drawable.ic_arrow_forward, where,
				() -> definition(word, keyboardUp()));
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
	 * List the uses of word at the caret, renaming at once when rename
	 * and word can be renamed: not for a built-in, and only where the
	 * folder lists uses.
	 */
	void uses(@NonNull String word, boolean rename) {
		if (!listsUses(word)) {
			return;
		}
		List<Knowledge.Symbol> places = definitions(word).places();
		boolean renamable = renames(word);
		host.uses(word, places.isEmpty() ? null : places.get(0).file(),
				keyboardUp(), renamable, rename && renamable);
	}

	/**
	 * Return whether the uses of word at the caret can be listed: a name
	 * of the file or the files around, or declared nowhere, in a folder.
	 */
	boolean listsUses(@NonNull String word) {
		return host.offersUses() && (GlslWords.style(word, 0,
				word.length()) == Style.IDENTIFIER ||
				!definitions(word).places().isEmpty());
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
	 * its own declaration list its uses; where it has none, as for a
	 * built-in, an app uniform the file lacks or a name declared
	 * nowhere, explain it.
	 */
	void definitionKey(@NonNull String word) {
		Definitions.Found found = definitions(word);
		if (found.places().isEmpty()) {
			lookUp(word);
		} else if (found.here()) {
			uses(word, false);
		} else {
			definition(word, keyboardUp());
		}
	}

	/** Where word, at the caret, is declared, see {@link Definitions}. */
	@NonNull
	Definitions.Found definitions(@NonNull String word) {
		return Definitions.of(host.declared(), host.scope(), host.text(),
				host.offset(), word);
	}

	/**
	 * Go where word, at the caret, is declared: there when it surely
	 * means that one declaration; else a list of the declarations known
	 * to choose from, after which the keyboard comes back if it was up.
	 * Return whether that list is shown.
	 */
	boolean definition(@NonNull String word, boolean keyboard) {
		Definitions.Found found = definitions(word);
		Knowledge.Symbol only = found.only();
		if (only != null) {
			host.goTo(only);
		} else if (!found.places().isEmpty()) {
			list(word, found, keyboard);
			return true;
		}
		return false;
	}

	// Lists the places found, the declarations of word, in the rows of
	// Uses, by file; with the one form the call at the caret fits,
	// named, never chosen; or, when there may be more, saying so.
	private void list(@NonNull String word, @NonNull Definitions.Found found,
			boolean keyboard) {
		List<Knowledge.Symbol> places = found.places();
		Context context = view.getContext();
		String shown = host.file();
		Pattern name = Pattern.compile("(?<![A-Za-z0-9_])" +
				Pattern.quote(word) + "(?![A-Za-z0-9_])");
		Map<String, List<WordPlaces.Place>> byFile = new LinkedHashMap<>();
		Map<WordPlaces.Place, Knowledge.Symbol> declared = new HashMap<>();
		List<WordPlaces.Place> rows = new ArrayList<>();
		for (Knowledge.Symbol place : places) {
			String file = place.file() != null ? place.file() : shown;
			String code = place.detail();
			Matcher at = name.matcher(code);
			WordPlaces.Place row = new WordPlaces.Place(file, place.start(),
					Math.max(0, place.file() != null
							? host.scope().line(place)
							: host.lineOf(place.start())),
					at.find() ? at.start() : 0, code, false);
			List<WordPlaces.Place> inFile = byFile.get(file);
			if (inFile == null) {
				inFile = new ArrayList<>();
				byFile.put(file, inFile);
			}
			inFile.add(row);
			rows.add(row);
			declared.put(row, place);
		}
		List<WordPlaces.Group> groups = new ArrayList<>();
		for (Map.Entry<String, List<WordPlaces.Place>> file :
				byFile.entrySet()) {
			groups.add(new WordPlaces.Group(WordPlaces.Group.Kind.FILE,
					file.getKey(), file.getValue()));
		}
		List<String> types = found.certain() ? host.arguments(word) : null;
		int fits = types != null && !types.contains(null)
				? Definitions.fitting(places, types)
				: -1;
		UsesSheet sheet = new UsesSheet(context,
				ShaderEditorApp.preferences.getFont(),
				row -> host.goTo(declared.get(row)));
		sheet.setOnDismiss(() -> view.post(() -> refocus(keyboard)));
		sheet.showList(new UsesSheet.Content(word, shown, groups, 0, false,
						false), context.getResources().getQuantityString(
						R.plurals.definitions, places.size(), places.size()),
				!found.certain()
						? context.getString(R.string.definitions_unsure)
						: fits < 0 ? null : context.getString(
						R.string.definition_fits,
						host.lineOf(host.offset()) + 1,
						TextUtils.join(", ", types),
						rows.get(fits).line() + 1));
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
