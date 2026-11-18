package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.CompletionsAdapter;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.syntax.GlslCompletion;
import de.markusfisch.android.shadereditor.widget.NameView;
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
	 * yet, what it is, for code the names it declares or null where they
	 * are not known, and what Show and Open do, each null when it is not
	 * offered.
	 */
	public record Reference(@NonNull String name, @Nullable Bitmap picture,
			@NonNull Kind kind, @Nullable String names,
			@Nullable Runnable show, @Nullable Runnable open) {
		public enum Kind {PASS, IMAGE, CODE}
	}

	@NonNull
	private final View extraKeysView;
	@NonNull
	private final View referenceView;
	@NonNull
	private final ImageView referencePicture;
	@NonNull
	private final TextView referenceNames;
	@NonNull
	private final View referenceShow;
	@NonNull
	private final View referenceOpen;
	@NonNull
	private final View nameRow;
	@NonNull
	private final NameView nameForm;
	@NonNull
	private final MaterialButton nameAction;
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
	// The keys in project mode, Tab first, then by how often GLSL needs
	// them and how far they are on the common keyboards. What does not
	// fit scrolls.
	private static final List<String> KEYS = List.of(CompletionsAdapter.TAB,
			"(", ")", ";", "=", "*", "{", "}");

	// Whether the row follows the rules of the new editor in project
	// mode; the release build's row stays as it was.
	private final boolean projectMode;
	private final int hairline;
	// Whether the completions are the keys.
	private boolean keysShown;
	private boolean hasText = true;
	// What stands in place of the completions, if anything.
	@Nullable
	private Reference reference;
	@Nullable
	private NameView.Name name;
	// While a number is tuned the bar stands where the keys are.
	private boolean covered;

	public ExtraKeysManager(@NonNull Activity activity,
			@NonNull View rootView, @NonNull Editor editor) {
		this.extraKeysView = rootView.findViewById(R.id.extra_keys);
		projectMode = activity.getResources().getBoolean(R.bool.project_mode);

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
		// What explains is looked up: "Double-tap to look up".
		ViewCompat.replaceAccessibilityAction(signatureView,
				AccessibilityNodeInfoCompat.AccessibilityActionCompat
						.ACTION_CLICK, activity.getString(R.string.look_up),
				null);
		referenceView = extraKeysView.findViewById(R.id.reference);
		referencePicture = extraKeysView.findViewById(R.id.reference_picture);
		referenceNames = extraKeysView.findViewById(R.id.reference_names);
		referenceNames.setTypeface(ShaderEditorApp.preferences.getFont());
		referenceShow = extraKeysView.findViewById(R.id.reference_show);
		referenceOpen = extraKeysView.findViewById(R.id.reference_open);
		nameRow = extraKeysView.findViewById(R.id.name);
		nameForm = extraKeysView.findViewById(R.id.name_form);
		nameForm.setOnClickListener(v -> {
			if (name != null) {
				name.open().run();
			}
		});
		nameForm.setOnLongClickListener(v -> {
			if (name != null) {
				name.open().run();
			}
			return true;
		});
		nameAction = extraKeysView.findViewById(R.id.name_action);
		// TalkBack: "Double-tap to look up", and a double-tap does
		// that, whatever a touch in its place would reach.
		ViewCompat.replaceAccessibilityAction(nameForm,
				AccessibilityNodeInfoCompat.AccessibilityActionCompat
						.ACTION_CLICK, activity.getString(R.string.look_up),
				(v, arguments) -> {
					if (name != null) {
						name.open().run();
					}
					return true;
				});
		backView = extraKeysView.findViewById(R.id.back_to_file);
		tuneView = extraKeysView.findViewById(R.id.tune);
		tuneView.setOnClickListener(v -> {
			if (onTune != null) {
				onTune.run();
			}
		});

		completions.setLayoutManager(new LinearLayoutManager(
				activity, RecyclerView.HORIZONTAL, false));
		completionsAdapter = new CompletionsAdapter((word, typed) -> {
			if (CompletionsAdapter.TAB.equals(word)) {
				editor.tab();
			} else {
				editor.complete(word, typed);
			}
		}, editor, projectMode);
		completions.setAdapter(completionsAdapter);
		if (projectMode) {
			// A list that gains a first item, as the keys do with Tab,
			// starts at it, not where the old first one was; the keys
			// fit again when their number or the room changes.
			completionsAdapter.registerAdapterDataObserver(
					new RecyclerView.AdapterDataObserver() {
						@Override
						public void onItemRangeInserted(int start, int count) {
							if (start == 0) {
								completions.scrollToPosition(0);
							}
							fitKeys();
						}

						@Override
						public void onItemRangeRemoved(int start, int count) {
							fitKeys();
						}
					});
			extraKeysView.addOnLayoutChangeListener((v, left, top, right,
					bottom, oldLeft, oldTop, oldRight, oldBottom) ->
					v.post(this::fitKeys));
		}

		Drawable line = Objects.requireNonNull(ContextCompat.getDrawable(
				activity, R.drawable.divider_with_padding_vertical));
		hairline = line.getIntrinsicWidth();
		completions.addItemDecoration(new Hairlines(line, projectMode));
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
		// The keys, Tab first unless the setting hides it; none with a
		// hardware keyboard, which has them.
		keysShown = projectMode && completions == GlslCompletion.KEYS &&
				!hardwareKeyboard();
		if (projectMode && completions == GlslCompletion.KEYS) {
			completions = hardwareKeyboard()
					? Collections.emptyList()
					: ShaderEditorApp.preferences.doesShowInsertTab()
					? KEYS
					: KEYS.subList(1, KEYS.size());
		}
		completionsAdapter.submitCompletions(completions, position);
		fitKeys();
	}

	// With a call the keys take the room they need, without one the
	// completions take all of it. In project mode the keys leave the
	// call's form the room of two keys at least, and the rest of them
	// scrolls: one item at the end. Where the keys do not all fit, their
	// room ends on half a key, which says that more can be swiped in:
	// the room is shortened before the item at the end, so no key moves.
	private void fitKeys() {
		LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)
				completions.getLayoutParams();
		boolean form = signatureView.getVisibility() == View.VISIBLE;
		int width = form ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
		int gap = 0;
		if (projectMode && extraKeysView.getWidth() > 0 &&
				(form || keysShown)) {
			int key = extraKeysView.getResources().getDimensionPixelSize(
					R.dimen.se_key_height);
			int n = completionsAdapter.getItemCount();
			int keys = n * key + Math.max(0, n - 1) * hairline;
			if (form) {
				int room = extraKeysView.getWidth() -
						extraKeysView.getPaddingLeft() -
						extraKeysView.getPaddingRight() - 2 * key -
						(backView.getVisibility() == View.VISIBLE
								? backView.getWidth() + ((ViewGroup
								.MarginLayoutParams) backView.getLayoutParams())
								.getMarginEnd()
								: 0);
				width = Math.max(key, Math.min(keys,
						room - halfKey(room, keys, key)));
			} else {
				int room = completions.getWidth() + params.rightMargin;
				gap = halfKey(room, keys, key);
			}
		}
		if (params.width != width || params.weight != (form ? 0 : 1) ||
				params.rightMargin != gap) {
			params.width = width;
			params.weight = form ? 0 : 1;
			params.rightMargin = gap;
			completions.setLayoutParams(params);
		}
	}

	// What room, for keys cells of width key one after the other, has
	// to give up so that its end cuts a key in half, through its symbol,
	// which stands in the middle of the key: nothing where they all
	// fit, nor where the end already cuts one between 45 % and 55 % of
	// its width; never a sliver.
	private int halfKey(int room, int keys, int key) {
		int pitch = key + hairline;
		if (keys <= room || room <= key) {
			return 0;
		}
		int rest = room % pitch;
		if (rest >= key * 45 / 100 && rest <= key * 55 / 100) {
			return 0;
		}
		return ((rest - key / 2) % pitch + pitch) % pitch;
	}

	// A hardware keyboard is attached and not hidden, by the device's
	// configuration; a cover or a dock may report one too.
	private boolean hardwareKeyboard() {
		Configuration config = extraKeysView.getResources()
				.getConfiguration();
		return config.keyboard == Configuration.KEYBOARD_QWERTY &&
				config.hardKeyboardHidden ==
						Configuration.HARDKEYBOARDHIDDEN_NO;
	}

	/**
	 * Show the call being written behind the keys, which keep their
	 * places while it fills the rest of the strip, or none for null.
	 * TalkBack hears a call once when it opens, not at every comma,
	 * and not while something else is in front.
	 */
	public void setSignature(@Nullable SignatureView.Signature signature) {
		SignatureView.Signature before = signatureView.getSignature();
		boolean shown = signature != null;
		if (shown != (before != null)) {
			signatureView.setVisibility(getVisibility(shown));
			fitKeys();
		}
		signatureView.setSignature(signature);
		// Not under a sheet or dialog, whose window has the focus.
		if (shown && signatureView.hasWindowFocus() && (before == null ||
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
		this.reference = reference;
		completions.setVisibility(getVisibility(reference == null &&
				name == null));
		referenceView.setVisibility(getVisibility(reference != null));
		if (reference == null) {
			return;
		}
		String name = reference.name();
		// Code shows the names it declares in place of an icon; a
		// picture or a pass its picture. Either way the actions end the
		// row.
		String names = reference.names();
		referencePicture.setVisibility(getVisibility(names == null));
		referenceNames.setText(names);
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

	private int actionWidth;

	/**
	 * Show the name the caret rests in in place of the completions,
	 * them when null. It is not announced: it would speak at every
	 * arrow key; the code's own actions are TalkBack's way to it.
	 */
	public void showName(@Nullable NameView.Name name) {
		NameView.Name was = this.name;
		this.name = name;
		if (name == null && was == null) {
			return;
		} else if (name != null && was != null && looksAlike(name, was)) {
			// The same item made again: nothing is drawn again.
			Runnable act = name.act();
			nameAction.setOnClickListener(act != null ? v -> act.run() : null);
			return;
		}
		completions.setVisibility(getVisibility(reference == null &&
				name == null));
		nameRow.setVisibility(getVisibility(name != null));
		if (name == null) {
			return;
		}
		nameForm.setForm(name.word(), name.form(), name.note());
		nameForm.setContentDescription(name.spoken());
		Runnable act = name.act();
		// While the action is found, its place is held, as wide as the
		// widest it can become, so that nothing moves when it comes.
		nameAction.setMinWidth(actionWidth());
		nameAction.setVisibility(act != null ? View.VISIBLE
				: name.waits() ? View.INVISIBLE : View.GONE);
		nameAction.setText(name.action());
		if (name.icon() != 0) {
			nameAction.setIconResource(name.icon());
		}
		nameAction.setContentDescription(name.actionSpoken());
		nameAction.setOnClickListener(act != null ? v -> act.run() : null);
	}

	private static boolean looksAlike(@NonNull NameView.Name a,
			@NonNull NameView.Name b) {
		return a.word().equals(b.word()) && a.form().equals(b.form()) &&
				Objects.equals(a.note(), b.note()) &&
				a.spoken().equals(b.spoken()) &&
				Objects.equals(a.action(), b.action()) &&
				a.icon() == b.icon() && a.waits() == b.waits() &&
				Objects.equals(a.actionSpoken(), b.actionSpoken());
	}

	// The width of the name's action with the wider of its labels.
	private int actionWidth() {
		if (actionWidth == 0) {
			Context context = nameAction.getContext();
			float widest = 0;
			for (int label : new int[]{R.string.definition, R.string.uses}) {
				widest = Math.max(widest, nameAction.getPaint().measureText(
						context.getString(label)));
			}
			actionWidth = (int) Math.ceil(widest) + nameAction.getIconSize() +
					nameAction.getIconPadding() + nameAction.getPaddingLeft() +
					nameAction.getPaddingRight();
		}
		return actionWidth;
	}

	/**
	 * Show the key back to the file named name at line, from 1, or to
	 * line of the file shown when name is null, in front of the keys,
	 * doing back; none when back is null.
	 */
	public void showBack(@Nullable String name, int line,
			@Nullable Runnable back) {
		Context context = backView.getContext();
		backView.setVisibility(getVisibility(back != null));
		backView.setText(name != null
				? name
				: context.getString(R.string.back_to_line, line));
		backView.setOnClickListener(back != null ? v -> back.run() : null);
		backView.setContentDescription(name == null
				? context.getString(R.string.a11y_back_to_line, line)
				: line > 0
				? context.getString(R.string.a11y_back_to_file, name, line)
				: context.getString(R.string.a11y_back_to, name));
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
		tuneView.setContentDescription(tuneView.getContext().getString(
				R.string.a11y_tune, number));
		tuneView.setVisibility(View.VISIBLE);
	}

	public void updateVisibility() {
		var preferences = ShaderEditorApp.preferences;
		setVisible(preferences.showExtraKeys());
		// In project mode Tab is one of the keys.
		insertTabView.setVisibility(getVisibility(!projectMode &&
				preferences.doesShowInsertTab()));
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

	// A hairline after each cell of what inserts; with notLast none after
	// the last, as what follows the keys, the call's form or Tune, is no
	// cell.
	private static final class Hairlines extends RecyclerView.ItemDecoration {
		@NonNull
		private final Drawable line;
		private final boolean notLast;

		private Hairlines(@NonNull Drawable line, boolean notLast) {
			this.line = line;
			this.notLast = notLast;
		}

		@Override
		public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
				@NonNull RecyclerView parent,
				@NonNull RecyclerView.State state) {
			outRect.set(0, 0, last(view, parent) ? 0
					: line.getIntrinsicWidth(), 0);
		}

		@Override
		public void onDraw(@NonNull Canvas canvas,
				@NonNull RecyclerView parent,
				@NonNull RecyclerView.State state) {
			for (int i = 0, n = parent.getChildCount(); i < n; ++i) {
				View child = parent.getChildAt(i);
				if (last(child, parent)) {
					continue;
				}
				int right = child.getRight() +
						Math.round(child.getTranslationX());
				line.setBounds(right, 0, right + line.getIntrinsicWidth(),
						parent.getHeight());
				line.draw(canvas);
			}
		}

		private boolean last(@NonNull View view,
				@NonNull RecyclerView parent) {
			RecyclerView.Adapter<?> adapter = parent.getAdapter();
			int position = parent.getChildAdapterPosition(view);
			return notLast && (adapter == null ||
					position == RecyclerView.NO_POSITION ||
					position == adapter.getItemCount() - 1);
		}
	}

	private static int getVisibility(boolean isVisible) {
		return isVisible ? View.VISIBLE : View.GONE;
	}
}
