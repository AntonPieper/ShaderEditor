package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;

/** Asks for a new name in an outlined field, with the keyboard up. */
public final class RenameDialog {
	public interface Listener {
		void onRename(@NonNull String name);
	}

	/** Says why a name cannot be taken, or null when it can. */
	public interface Checker {
		@Nullable
		String check(@NonNull String name);
	}

	private RenameDialog() {
	}

	public static void show(@NonNull Context context, @NonNull String name,
			@NonNull Listener listener) {
		show(context, R.string.rename_shader, name, null, null, listener);
	}

	/**
	 * Ask under title, with message above the field when not null; a
	 * name checker refuses cannot be confirmed, and the field says why.
	 * Of a name with an extension, the part before it is selected.
	 */
	public static void show(@NonNull Context context, @StringRes int title,
			@NonNull String name, @Nullable Checker checker,
			@Nullable CharSequence message, @NonNull Listener listener) {
		View view = LayoutInflater.from(context).inflate(
				R.layout.dialog_rename_shader, null);
		TextInputLayout nameLayout = view.findViewById(R.id.name_layout);
		EditText nameView = view.findViewById(R.id.name);
		nameView.setText(name);
		int dot = checker != null ? name.lastIndexOf('.') : -1;
		nameView.setSelection(0, dot > 0 ? dot : name.length());
		AlertDialog dialog = new MaterialAlertDialogBuilder(context)
				.setTitle(title)
				.setMessage(message)
				.setView(view)
				.setPositiveButton(R.string.nav_rename, (d, which) -> {
					SoftKeyboard.hide(context, nameView);
					listener.onRename(nameView.getText().toString());
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		nameView.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId != EditorInfo.IME_ACTION_DONE ||
					!dialog.getButton(DialogInterface.BUTTON_POSITIVE)
							.isEnabled()) {
				return false;
			}
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
			return true;
		});
		Window window = dialog.getWindow();
		if (window != null) {
			window.setSoftInputMode(
					WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
		}
		dialog.show();
		nameView.requestFocus();
		if (checker == null) {
			return;
		}
		View rename = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
		Runnable check = () -> {
			String problem = checker.check(nameView.getText().toString()
					.trim());
			nameLayout.setError(problem != null && !problem.isEmpty()
					? problem
					: null);
			rename.setEnabled(problem == null);
		};
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
				check.run();
			}
		});
		check.run();
	}
}
