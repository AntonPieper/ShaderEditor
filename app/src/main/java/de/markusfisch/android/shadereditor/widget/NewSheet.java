package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputLayout;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Naming;

/**
 * The sheet that creates something new: its kind, where a folder can
 * hold more than shaders, its name and where it goes. A name the
 * folder cannot take is refused under the field.
 */
public final class NewSheet {
	public enum Kind {
		SHADER,
		EMPTY,
		FOLDER
	}

	public interface Listener {
		/** Create kind as name; the sheet stays until dismissed. */
		void onCreate(@NonNull Kind kind, @NonNull String name);

		/** Choose another folder to create in. */
		void onChangeLocation();

		void onDismissed();
	}

	private final Context context;
	private final BottomSheetDialog dialog;
	private final MaterialButtonToggleGroup kinds;
	private final TextInputLayout nameLayout;
	private final EditText nameView;
	private final TextView location;
	private final View create;
	private final String untitled;
	// The names in the folder, or null while they are read or in the
	// library, where names may repeat and may be left out.
	@Nullable
	private Naming naming;
	private boolean inFolder;
	private boolean ready;
	// Whether the name is the one suggested, which follows the kind.
	private boolean suggested = true;
	private boolean filling;

	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public NewSheet(@NonNull Context context, @NonNull Kind kind,
			@NonNull Listener listener) {
		this.context = context;
		untitled = context.getString(R.string.nav_untitled);
		View view = LayoutInflater.from(context).inflate(R.layout.sheet_new,
				null);
		kinds = view.findViewById(R.id.new_kind);
		nameLayout = view.findViewById(R.id.new_name_layout);
		nameView = view.findViewById(R.id.new_name);
		location = view.findViewById(R.id.new_location);
		create = view.findViewById(R.id.new_create);
		kinds.check(switch (kind) {
			case SHADER -> R.id.new_shader;
			case EMPTY -> R.id.new_empty;
			case FOLDER -> R.id.new_folder;
		});
		kinds.addOnButtonCheckedListener((group, id, checked) -> {
			if (checked) {
				suggest();
			}
		});
		nameView.addTextChangedListener(new TextWatcher() {
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
				if (!filling) {
					suggested = false;
				}
				check();
			}
		});
		nameView.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE && create.isEnabled()) {
				create.performClick();
				return true;
			}
			return false;
		});
		create.setOnClickListener(v -> listener.onCreate(kind(),
				nameView.getText().toString().trim()));
		view.findViewById(R.id.new_change).setOnClickListener(v ->
				listener.onChangeLocation());
		dialog = new BottomSheetDialog(context);
		view.findViewById(R.id.new_cancel).setOnClickListener(v ->
				dialog.dismiss());
		dialog.setContentView(view);
		dialog.setOnDismissListener(d -> listener.onDismissed());
		BottomSheetBehavior<?> behavior = dialog.getBehavior();
		behavior.setSkipCollapsed(true);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		if (dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams
					.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
		}
	}

	/** Creates a database shader in the library, which takes any name. */
	public void showLibrary(@NonNull String where) {
		inFolder = false;
		naming = null;
		ready = true;
		kinds.setVisibility(View.GONE);
		location.setText(where);
		fill("");
		show();
	}

	/**
	 * Creates in a folder, at where, once its names are known through
	 * {@link #setNames}.
	 */
	public void showFolder(@NonNull String where) {
		inFolder = true;
		naming = null;
		ready = false;
		kinds.setVisibility(View.VISIBLE);
		location.setText(where);
		check();
		show();
	}

	/** Takes the names in the folder, so that the name can be checked. */
	public void setNames(@NonNull Naming naming) {
		this.naming = naming;
		ready = true;
		if (suggested) {
			suggest();
		} else {
			check();
		}
	}

	/** Says why creating failed; the sheet stays open. */
	public void fail(@NonNull String reason) {
		nameLayout.setError(reason);
	}

	public void dismiss() {
		dialog.dismiss();
	}

	private void show() {
		if (!dialog.isShowing()) {
			dialog.show();
		}
		nameView.requestFocus();
	}

	@NonNull
	private Kind kind() {
		if (!inFolder) {
			return Kind.SHADER;
		}
		int id = kinds.getCheckedButtonId();
		return id == R.id.new_folder
				? Kind.FOLDER
				: id == R.id.new_empty ? Kind.EMPTY : Kind.SHADER;
	}

	// An unused name, with the part before the extension selected.
	private void suggest() {
		if (naming == null || !suggested) {
			check();
			return;
		}
		String extension = kind() == Kind.FOLDER ? "" : ".glsl";
		fill(naming.unused(untitled, extension));
	}

	private void fill(@NonNull String name) {
		filling = true;
		nameView.setText(name);
		filling = false;
		int dot = name.lastIndexOf('.');
		nameView.setSelection(0, dot > 0 ? dot : name.length());
		suggested = true;
		check();
	}

	private void check() {
		String name = nameView.getText().toString().trim();
		Naming.Problem problem = !inFolder || naming == null
				? Naming.Problem.NONE
				: naming.check(name);
		nameLayout.setError(switch (problem) {
			case INVALID_CHARACTER -> context.getString(
					R.string.nav_name_invalid);
			case HIDDEN -> context.getString(R.string.nav_name_hidden);
			case TAKEN -> context.getString(R.string.nav_name_taken, name);
			default -> null;
		});
		create.setEnabled(ready && problem == Naming.Problem.NONE &&
				(!inFolder || !name.isEmpty()));
	}
}
