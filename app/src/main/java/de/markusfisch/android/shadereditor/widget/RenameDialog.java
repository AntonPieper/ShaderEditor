package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;

/** Asks for a new name in an outlined field, with the keyboard up. */
public final class RenameDialog {
	public interface Listener {
		void onRename(@NonNull String name);
	}

	private RenameDialog() {
	}

	public static void show(@NonNull Context context, @NonNull String name,
			@NonNull Listener listener) {
		View view = LayoutInflater.from(context).inflate(
				R.layout.dialog_rename_shader, null);
		EditText nameView = view.findViewById(R.id.name);
		nameView.setText(name);
		nameView.selectAll();
		AlertDialog dialog = new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.rename_shader)
				.setView(view)
				.setPositiveButton(R.string.nav_rename, (d, which) -> {
					SoftKeyboard.hide(context, nameView);
					listener.onRename(nameView.getText().toString());
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		nameView.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId != EditorInfo.IME_ACTION_DONE) {
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
	}
}
