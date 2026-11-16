package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.syntax.Knowledge;

/**
 * Go to, in a sheet above the keyboard: one field over the declarations
 * of the file and of the files it includes. A name narrows the list, a
 * number goes to that line, and Enter takes the first row.
 */
public final class GoToSheet {
	/** Goes to a row the user chose. */
	public interface Go {
		void go(@NonNull Row row);
	}

	/**
	 * A row: a declaration, the path of an included file over its
	 * declarations, or a line.
	 *
	 * @param file       the path of an included file, null for this one
	 * @param name       the name, the path of a heading, or a line's text
	 * @param parameters the parameters of a function or a macro in
	 *                   parentheses, else ""
	 * @param line       the line, from 0; -1 when it is not known
	 */
	public record Row(@NonNull Kind kind, @Nullable String file,
			@NonNull String name, @NonNull String parameters, int line) {
		public enum Kind {DECLARATION, HEADING, LINE}
	}

	private final BottomSheetDialog dialog;
	private final Adapter adapter;

	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public GoToSheet(@NonNull Context context, @NonNull List<Row> outline,
			@NonNull Text text, @NonNull Typeface font, @NonNull Go go) {
		View view = LayoutInflater.from(context).inflate(R.layout.sheet_go_to,
				null);
		dialog = new BottomSheetDialog(context);
		adapter = new Adapter(font, MaterialColors.getColor(view,
				androidx.appcompat.R.attr.colorPrimary),
				MaterialColors.getColor(view, com.google.android.material.R
						.attr.colorOnSurfaceVariant), row -> {
			dialog.dismiss();
			go.go(row);
		});
		RecyclerView list = view.findViewById(R.id.go_to_list);
		list.setLayoutManager(new LinearLayoutManager(context));
		list.setAdapter(adapter);
		EditText field = view.findViewById(R.id.go_to_field);
		field.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start,
					int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				adapter.show(narrow(outline, s.toString(), text),
						s.toString().trim());
			}
		});
		field.setOnEditorActionListener((v, action, event) -> {
			if (action == EditorInfo.IME_ACTION_GO || (event != null &&
					event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
					event.getAction() == KeyEvent.ACTION_DOWN)) {
				Row first = adapter.first();
				if (first != null) {
					dialog.dismiss();
					go.go(first);
				}
				return true;
			}
			return false;
		});
		adapter.show(outline, "");
		dialog.setContentView(view);
		BottomSheetBehavior<?> behavior = dialog.getBehavior();
		behavior.setSkipCollapsed(true);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		dialog.setOnShowListener(d -> {
			// From the top to the keyboard, so that the list is never
			// under it.
			View sheet = dialog.findViewById(com.google.android.material.R
					.id.design_bottom_sheet);
			if (sheet != null) {
				sheet.getLayoutParams().height =
						ViewGroup.LayoutParams.MATCH_PARENT;
				sheet.requestLayout();
			}
			field.requestFocus();
		});
		if (dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams
					.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
					WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		}
	}

	public void show() {
		dialog.show();
	}

	/**
	 * Return what Go to lists for a file of text that knows known and
	 * sees scope: its declarations in its order, then each included
	 * file's under its path, the paths in order, each file's
	 * declarations by their lines.
	 */
	@NonNull
	public static List<Row> outline(@NonNull Knowledge known,
			@NonNull Text text, @NonNull Knowledge.Scope scope) {
		List<Row> rows = new ArrayList<>();
		for (Knowledge.Symbol symbol : known.symbols()) {
			rows.add(row(symbol, text.lineOf(Math.max(0,
					Math.min(symbol.start(), text.length())))));
		}
		Map<String, List<Row>> files = new TreeMap<>();
		for (String name : scope.names()) {
			for (Knowledge.Symbol symbol : scope.find(name)) {
				if (symbol.file() == null) {
					continue;
				}
				List<Row> declared = files.get(symbol.file());
				if (declared == null) {
					declared = new ArrayList<>();
					files.put(symbol.file(), declared);
				}
				declared.add(row(symbol, scope.line(symbol)));
			}
		}
		for (Map.Entry<String, List<Row>> file : files.entrySet()) {
			rows.add(new Row(Row.Kind.HEADING, file.getKey(), file.getKey(),
					"", -1));
			List<Row> declared = file.getValue();
			Collections.sort(declared, (a, b) -> Integer.compare(a.line(),
					b.line()));
			rows.addAll(declared);
		}
		return rows;
	}

	/**
	 * Return the rows of outline for typed: all for nothing, the one
	 * line of a number, else in each file first the names that start
	 * with it, then those that contain it, in any case.
	 */
	@NonNull
	public static List<Row> narrow(@NonNull List<Row> outline,
			@NonNull String typed, @NonNull Text text) {
		String query = typed.trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) {
			return outline;
		} else if (isNumber(query)) {
			int line = query.length() > 9
					? text.lineCount() - 1
					: Math.max(0, Math.min(Integer.parseInt(query),
					text.lineCount()) - 1);
			return List.of(new Row(Row.Kind.LINE, null,
					text.line(line).trim(), "", line));
		}
		List<Row> rows = new ArrayList<>();
		List<Row> starting = new ArrayList<>();
		List<Row> containing = new ArrayList<>();
		Row heading = null;
		for (int i = 0, size = outline.size(); i <= size; ++i) {
			Row row = i < size ? outline.get(i) : null;
			if (row == null || row.kind() == Row.Kind.HEADING) {
				if (!starting.isEmpty() || !containing.isEmpty()) {
					if (heading != null) {
						rows.add(heading);
					}
					rows.addAll(starting);
					rows.addAll(containing);
				}
				starting.clear();
				containing.clear();
				heading = row;
				continue;
			}
			int at = row.name().toLowerCase(Locale.ROOT).indexOf(query);
			if (at == 0) {
				starting.add(row);
			} else if (at > 0) {
				containing.add(row);
			}
		}
		return rows;
	}

	@NonNull
	private static Row row(@NonNull Knowledge.Symbol symbol, int line) {
		String parameters = "";
		boolean function = symbol.kind() == Knowledge.Kind.FUNCTION;
		if (function || (symbol.kind() == Knowledge.Kind.MACRO &&
				symbol.detail().contains(symbol.name() + "("))) {
			StringBuilder sb = new StringBuilder("(");
			for (Knowledge.Symbol part : symbol.parts()) {
				if (sb.length() > 1) {
					sb.append(", ");
				}
				sb.append(part.name());
			}
			parameters = sb.append(')').toString();
		}
		return new Row(Row.Kind.DECLARATION, symbol.file(), symbol.name(),
				parameters, line);
	}

	private static boolean isNumber(@NonNull String s) {
		for (int i = s.length(); i-- > 0; ) {
			if (s.charAt(i) < '0' || s.charAt(i) > '9') {
				return false;
			}
		}
		return true;
	}

	private static final class Adapter
			extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private final Typeface font;
		private final int typedColor;
		private final int dimColor;
		private final Go go;
		@NonNull
		private List<Row> rows = Collections.emptyList();
		@NonNull
		private String typed = "";

		private Adapter(@NonNull Typeface font, int typedColor, int dimColor,
				@NonNull Go go) {
			this.font = font;
			this.typedColor = typedColor;
			this.dimColor = dimColor;
			this.go = go;
		}

		// The rows of rows, of which typed narrowed them.
		@SuppressLint("NotifyDataSetChanged")
		private void show(@NonNull List<Row> rows, @NonNull String typed) {
			this.rows = rows;
			this.typed = typed;
			notifyDataSetChanged();
		}

		@Nullable
		private Row first() {
			for (Row row : rows) {
				if (row.kind() != Row.Kind.HEADING) {
					return row;
				}
			}
			return null;
		}

		@Override
		public int getItemViewType(int position) {
			return rows.get(position).kind() == Row.Kind.HEADING ? 1 : 0;
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int type) {
			View view = LayoutInflater.from(parent.getContext()).inflate(
					type == 1 ? R.layout.row_go_to_heading : R.layout.row_go_to,
					parent, false);
			if (type == 1) {
				return new RecyclerView.ViewHolder(view) {
				};
			}
			((TextView) view.findViewById(R.id.go_to_name)).setTypeface(font);
			return new RecyclerView.ViewHolder(view) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
				int position) {
			Row row = rows.get(position);
			View view = holder.itemView;
			if (row.kind() == Row.Kind.HEADING) {
				((TextView) view).setText(row.name());
				return;
			}
			TextView label = view.findViewById(R.id.go_to_label);
			TextView name = view.findViewById(R.id.go_to_name);
			TextView line = view.findViewById(R.id.go_to_line);
			Context context = view.getContext();
			boolean isLine = row.kind() == Row.Kind.LINE;
			label.setVisibility(isLine ? View.VISIBLE : View.GONE);
			line.setVisibility(isLine || row.line() < 0
					? View.GONE
					: View.VISIBLE);
			if (isLine) {
				label.setText(context.getString(R.string.go_to_line_row,
						row.line() + 1));
				name.setText(row.name());
			} else {
				name.setText(spans(row));
				line.setText(String.valueOf(row.line() + 1));
			}
			view.setOnClickListener(v -> go.go(row));
		}

		// The name, the part typed in the accent, then the parameters
		// dimmed.
		@NonNull
		private CharSequence spans(@NonNull Row row) {
			String name = row.name();
			SpannableString s = new SpannableString(name + row.parameters());
			int at = typed.isEmpty()
					? -1
					: name.toLowerCase(Locale.ROOT).indexOf(
					typed.toLowerCase(Locale.ROOT));
			if (at > -1) {
				s.setSpan(new ForegroundColorSpan(typedColor), at,
						at + typed.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
			if (!row.parameters().isEmpty()) {
				s.setSpan(new ForegroundColorSpan(dimColor), name.length(),
						s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
			return s;
		}
	}
}
