package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import de.markusfisch.android.shadereditor.widget.SignatureView;

public class ExtraKeysManager implements ViewTreeObserver.OnGlobalFocusChangeListener {
	public interface Editor extends CompletionsAdapter.LookUp {
		void insert(@NonNull CharSequence text);

		/** Do what the Tab key does. */
		void tab();
	}

	@NonNull
	private final View extraKeysView;
	@NonNull
	private final View insertTabView;
	@NonNull
	private final CompletionsAdapter completionsAdapter;
	@NonNull
	private final RecyclerView completions;
	@NonNull
	private final SignatureView signatureView;
	private boolean hasText = true;

	public ExtraKeysManager(@NonNull Activity activity,
			@NonNull View rootView, @NonNull Editor editor) {
		this.extraKeysView = rootView.findViewById(R.id.extra_keys);

		insertTabView = extraKeysView.findViewById(R.id.insert_tab);
		insertTabView.setOnClickListener(v -> editor.tab());

		completions = extraKeysView.findViewById(R.id.completions);
		signatureView = extraKeysView.findViewById(R.id.signature);
		// A tap or a long press explains the function called.
		signatureView.setOnClickListener(v -> lookUp(editor));
		signatureView.setOnLongClickListener(v -> {
			lookUp(editor);
			return true;
		});
		completions.setLayoutManager(new LinearLayoutManager(
				activity, RecyclerView.HORIZONTAL, false));
		completionsAdapter = new CompletionsAdapter(editor::insert, editor);
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

	/**
	 * Show the call being written behind the keys, which keep their
	 * places while it fills the rest of the strip, or none for null.
	 * TalkBack hears a call once when it opens, not at every comma.
	 */
	public void setSignature(@Nullable SignatureView.Signature signature) {
		SignatureView.Signature before = signatureView.getSignature();
		boolean shown = signature != null;
		if (shown != (before != null)) {
			signatureView.setVisibility(getVisibility(shown));
			// With a call the keys take the room they need, without
			// one the completions take all of it.
			LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)
					completions.getLayoutParams();
			params.width = shown ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
			params.weight = shown ? 0 : 1;
			completions.setLayoutParams(params);
		}
		signatureView.setSignature(signature);
		if (shown && (before == null ||
				!before.name().equals(signature.name()))) {
			signatureView.announceForAccessibility(
					signatureView.getContext().getString(
							R.string.a11y_signature_open, signature.name(),
							String.join(", ", signature.parameters())));
		}
	}

	private void lookUp(@NonNull Editor editor) {
		SignatureView.Signature signature = signatureView.getSignature();
		if (signature != null && editor.hasDocs(signature.name())) {
			editor.lookUp(signature.name());
		}
	}

	public void updateVisibility() {
		var preferences = ShaderEditorApp.preferences;
		setVisible(preferences.showExtraKeys());
		insertTabView.setVisibility(
				getVisibility(preferences.doesShowInsertTab()));
	}

	public void setVisible(boolean visible) {
		extraKeysView.setVisibility(getVisibility(visible && hasText &&
				(!ShaderEditorApp.preferences.autoHideExtraKeys() ||
						isKeyboardOpen())));
	}

	/** Without a text to insert into, the keys are not shown. */
	public void setHasText(boolean hasText) {
		this.hasText = hasText;
		setVisible(ShaderEditorApp.preferences.showExtraKeys());
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
