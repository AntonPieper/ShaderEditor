package de.markusfisch.android.shadereditor.fragment;

import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;

/** A list setting in the same Material dialog as every other dialog. */
public class ListPreferenceDialogFragment
		extends MaterialPreferenceDialogFragmentCompat {
	private static final String CLICKED_INDEX = "clicked_index";

	private int clickedIndex = -1;

	@NonNull
	public static ListPreferenceDialogFragment newInstance(String key) {
		Bundle bundle = new Bundle();
		bundle.putString(ARG_KEY, key);
		ListPreferenceDialogFragment fragment =
				new ListPreferenceDialogFragment();
		fragment.setArguments(bundle);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		ListPreference preference = getListPreference();
		clickedIndex = state == null
				? preference.findIndexOfValue(preference.getValue())
				: state.getInt(CLICKED_INDEX, -1);
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle state) {
		super.onSaveInstanceState(state);
		state.putInt(CLICKED_INDEX, clickedIndex);
	}

	@Override
	protected void onPrepareDialogBuilder(@NonNull AlertDialog.Builder builder) {
		builder.setSingleChoiceItems(getListPreference().getEntries(),
				clickedIndex, (dialog, which) -> {
					clickedIndex = which;
					onClick(dialog, DialogInterface.BUTTON_POSITIVE);
					dialog.dismiss();
				});
		builder.setPositiveButton(null, null);
	}

	@Override
	void onMaterialDialogClosed(boolean positiveResult) {
		ListPreference preference = getListPreference();
		CharSequence[] values = preference.getEntryValues();
		if (!positiveResult || clickedIndex < 0 || values == null ||
				clickedIndex >= values.length) {
			return;
		}
		String value = values[clickedIndex].toString();
		if (preference.callChangeListener(value)) {
			preference.setValue(value);
		}
	}

	@NonNull
	private ListPreference getListPreference() {
		return (ListPreference) getPreference();
	}
}
