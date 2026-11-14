package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.CompletionsAdapter;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

public class ExtraKeysManager implements ViewTreeObserver.OnGlobalFocusChangeListener {
	public interface Editor {
		void insert(@NonNull CharSequence text);
	}

	@NonNull
	private final View extraKeysView;
	@NonNull
	private final View insertTabView;
	@NonNull
	private final CompletionsAdapter completionsAdapter;

	public ExtraKeysManager(@NonNull Activity activity,
			@NonNull View rootView, @NonNull Editor editor) {
		this.extraKeysView = rootView.findViewById(R.id.extra_keys);

		insertTabView = extraKeysView.findViewById(R.id.insert_tab);
		insertTabView.setOnClickListener(v -> editor.insert("\t"));

		RecyclerView completions = extraKeysView.findViewById(R.id.completions);
		completions.setLayoutManager(new LinearLayoutManager(
				activity, RecyclerView.HORIZONTAL, false));
		completionsAdapter = new CompletionsAdapter(editor::insert);
		completions.setAdapter(completionsAdapter);

		var divider = new DividerItemDecoration(
				completions.getContext(), DividerItemDecoration.HORIZONTAL);
		divider.setDrawable(Objects.requireNonNull(ContextCompat.getDrawable(
				activity, R.drawable.divider_with_padding_vertical)));
		completions.addItemDecoration(divider);
		ViewCompat.setOnApplyWindowInsetsListener(extraKeysView, (v, insets) -> {
			setVisible(ShaderEditorApp.preferences.showExtraKeys());
			return insets;
		});
		extraKeysView.getViewTreeObserver().addOnGlobalFocusChangeListener(this);

		updateVisibility();
	}

	// A screen made again by recreate() keeps its window, and with it
	// this observer: stop listening, or it keeps the old screen alive.
	public void destroy() {
		ViewTreeObserver observer = extraKeysView.getViewTreeObserver();
		if (observer.isAlive()) {
			observer.removeOnGlobalFocusChangeListener(this);
		}
	}

	public void setCompletions(List<String> completions, int position) {
		completionsAdapter.submitCompletions(completions, position);
	}

	public void updateVisibility() {
		var preferences = ShaderEditorApp.preferences;
		setVisible(preferences.showExtraKeys());
		insertTabView.setVisibility(
				getVisibility(preferences.doesShowInsertTab()));
	}

	public void setVisible(boolean visible) {
		extraKeysView.setVisibility(getVisibility(visible &&
				(!ShaderEditorApp.preferences.autoHideExtraKeys() ||
						isKeyboardOpen())));
	}

	@Override
	public void onGlobalFocusChanged(View oldFocus, View newFocus) {
		setVisible(ShaderEditorApp.preferences.showExtraKeys());
	}

	// An on-screen keyboard is open (docked or floating), or a hardware
	// keyboard is attached while text has the focus.
	private boolean isKeyboardOpen() {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(extraKeysView);
		if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
			return true;
		}
		Configuration config = extraKeysView.getResources().getConfiguration();
		View focus = extraKeysView.getRootView().findFocus();
		return config.keyboard != Configuration.KEYBOARD_NOKEYS &&
				config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO &&
				focus != null && focus.onCheckIsTextEditor();
	}

	private static int getVisibility(boolean isVisible) {
		return isVisible ? View.VISIBLE : View.GONE;
	}
}
