package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ImageView;

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
		/** Put in word, of which typed characters are typed already. */
		void complete(@NonNull String word, int typed);

		/** Do what the Tab key does. */
		void tab();
	}

	/**
	 * The file the caret rests in the path of, as the strip shows it in
	 * place of the completions: its name, its picture or null for none
	 * yet, what it is, and what Show and Open do, each null when it is
	 * not offered.
	 */
	public record Reference(@NonNull String name, @Nullable Bitmap picture,
			@NonNull Kind kind, @Nullable Runnable show,
			@Nullable Runnable open) {
		public enum Kind {PASS, IMAGE, CODE}
	}

	@NonNull
	private final View extraKeysView;
	@NonNull
	private final View referenceView;
	@NonNull
	private final ImageView referencePicture;
	@NonNull
	private final View referenceShow;
	@NonNull
	private final View referenceOpen;
	@NonNull
	private final Button backView;
	@NonNull
	private final View insertTabView;
	@NonNull
	private final CompletionsAdapter completionsAdapter;
	@NonNull
	private final RecyclerView completions;
	@NonNull
	private final SignatureView signatureView;
	@NonNull
	private final android.widget.Button tuneView;
	@androidx.annotation.Nullable
	private Runnable onTune;
	private boolean hasText = true;
	// While a number is tuned the bar stands where the keys are.
	private boolean covered;

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
		referenceView = extraKeysView.findViewById(R.id.reference);
		referencePicture = extraKeysView.findViewById(R.id.reference_picture);
		referenceShow = extraKeysView.findViewById(R.id.reference_show);
		referenceOpen = extraKeysView.findViewById(R.id.reference_open);
		backView = extraKeysView.findViewById(R.id.back_to_file);
		tuneView = extraKeysView.findViewById(R.id.tune);
		tuneView.setTypeface(ShaderEditorApp.preferences.getFont());
		tuneView.setOnClickListener(v -> {
			if (onTune != null) {
				onTune.run();
			}
		});

		completions.setLayoutManager(new LinearLayoutManager(
				activity, RecyclerView.HORIZONTAL, false));
		completionsAdapter = new CompletionsAdapter(editor::complete, editor);
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

	/** Show reference in place of the completions, them when null. */
	public void showReference(@Nullable Reference reference) {
		completions.setVisibility(getVisibility(reference == null));
		referenceView.setVisibility(getVisibility(reference != null));
		if (reference == null) {
			return;
		}
		String name = reference.name();
		if (reference.picture() != null) {
			referencePicture.setScaleType(ImageView.ScaleType.CENTER_CROP);
			referencePicture.setImageBitmap(reference.picture());
		} else {
			referencePicture.setScaleType(ImageView.ScaleType.FIT_CENTER);
			referencePicture.setImageResource(switch (reference.kind()) {
				case CODE -> R.drawable.ic_code;
				case IMAGE -> R.drawable.ic_image;
				case PASS -> R.drawable.picture_empty_frame;
			});
		}
		referencePicture.setContentDescription(extraKeysView.getContext()
				.getString(switch (reference.kind()) {
					case CODE -> R.string.a11y_reference_include;
					case IMAGE -> R.string.a11y_reference_image;
					case PASS -> R.string.a11y_reference_pass;
				}, name));
		bind(referenceShow, reference.show(), R.string.a11y_reference_show,
				name);
		bind(referenceOpen, reference.open(), R.string.a11y_reference_open,
				name);
	}

	/**
	 * Show the key back to the file named name at line, from 1, in
	 * front of the keys, doing back; none when back is null.
	 */
	public void showBack(@NonNull String name, int line,
			@Nullable Runnable back) {
		backView.setVisibility(getVisibility(back != null));
		backView.setText(name);
		backView.setOnClickListener(back != null ? v -> back.run() : null);
		backView.setContentDescription(backView.getContext().getString(
				R.string.a11y_back_to_file, name, line));
	}

	private static void bind(@NonNull View button, @Nullable Runnable action,
			int spoken, @NonNull String name) {
		button.setVisibility(getVisibility(action != null));
		button.setOnClickListener(action != null ? v -> action.run() : null);
		button.setContentDescription(button.getContext().getString(spoken,
				name));
	}

	/**
	 * Offer to tune number, the one the caret is on, with onTune; none
	 * for null.
	 */
	public void setTunable(@androidx.annotation.Nullable String number,
			@androidx.annotation.Nullable Runnable onTune) {
		this.onTune = onTune;
		if (number == null || onTune == null) {
			tuneView.setVisibility(View.GONE);
			return;
		}
		tuneView.setText(tuneView.getContext().getString(R.string.tune_offer,
				number));
		tuneView.setContentDescription(tuneView.getContext().getString(
				R.string.a11y_tune, number));
		tuneView.setVisibility(View.VISIBLE);
	}

	public void updateVisibility() {
		var preferences = ShaderEditorApp.preferences;
		setVisible(preferences.showExtraKeys());
		insertTabView.setVisibility(
				getVisibility(preferences.doesShowInsertTab()));
	}

	/** Hide the keys while something else stands where they are. */
	public void setCovered(boolean covered) {
		this.covered = covered;
		setVisible(ShaderEditorApp.preferences.showExtraKeys());
	}

	public void setVisible(boolean visible) {
		extraKeysView.setVisibility(getVisibility(visible && hasText &&
				!covered && !inFind() &&
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

	// Whether a field of Find has the focus: the keys are for the code,
	// and step aside then.
	private boolean inFind() {
		View focus = extraKeysView.getRootView().findFocus();
		for (View view = focus; view != null;
				view = view.getParent() instanceof View parent
						? parent
						: null) {
			if (view.getId() == R.id.find_bar) {
				return true;
			}
		}
		return false;
	}

	// An on-screen keyboard is open, docked or floating.
	private boolean isKeyboardOpen() {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(extraKeysView);
		return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
	}

	private static int getVisibility(boolean isVisible) {
		return isVisible ? View.VISIBLE : View.GONE;
	}
}
