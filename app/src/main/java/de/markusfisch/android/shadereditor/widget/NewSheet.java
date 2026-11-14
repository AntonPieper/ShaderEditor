package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import de.markusfisch.android.shadereditor.R;

/** The sheet that creates something new: its name and where it goes. */
public final class NewSheet {
	public interface Listener {
		/** Create it as name, which may be empty. */
		void onCreate(@NonNull String name);

		void onDismissed();
	}

	private final BottomSheetDialog dialog;
	private final EditText nameView;
	private final TextView location;

	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public NewSheet(@NonNull Context context, @NonNull Listener listener) {
		View view = LayoutInflater.from(context).inflate(R.layout.sheet_new,
				null);
		nameView = view.findViewById(R.id.new_name);
		location = view.findViewById(R.id.new_location);
		View create = view.findViewById(R.id.new_create);
		nameView.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				create.performClick();
				return true;
			}
			return false;
		});
		create.setOnClickListener(v -> listener.onCreate(
				nameView.getText().toString().trim()));
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

	/**
	 * Creates a database shader in the library, at where, which takes
	 * any name and none.
	 */
	public void showLibrary(@NonNull String where) {
		location.setText(where);
		dialog.show();
		nameView.requestFocus();
	}

	public void dismiss() {
		dialog.dismiss();
	}
}
