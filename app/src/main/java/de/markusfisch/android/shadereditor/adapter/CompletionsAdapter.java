package de.markusfisch.android.shadereditor.adapter;

import android.annotation.SuppressLint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

public class CompletionsAdapter extends ListAdapter<String, CompletionsAdapter.ViewHolder> {
	@FunctionalInterface
	public interface OnInsertListener {
		/** word was chosen; typed of its characters are typed already. */
		void onChoose(@NonNull String word, int typed);
	}

	/** Explains words; a long press on a word asks it. */
	public interface LookUp {
		boolean hasDocs(@NonNull String word);

		void lookUp(@NonNull String word);
	}

	private static final DiffUtil.ItemCallback<String> DIFF_CALLBACK = new StringDiffer();

	/** Tab among the keys: a key with the tab icon that inserts a tab. */
	public static final String TAB = "\t";

	private final OnInsertListener onInsertListener;
	private final LookUp lookUp;
	// Whether TalkBack hears "Double-tap to insert".
	private final boolean saysInsert;

	private int position = 0;

	public CompletionsAdapter(OnInsertListener onInsertListener,
			LookUp lookUp, boolean saysInsert) {
		super(DIFF_CALLBACK);
		this.onInsertListener = onInsertListener;
		this.lookUp = lookUp;
		this.saysInsert = saysInsert;
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.extra_key_btn,
				parent, false);
		return new ViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		String item = getItem(position);
		holder.update(item);
	}

	public void submitCompletions(@NonNull List<String> completions, int position) {
		submitList(completions, () -> {
			if (this.position != position) {
				this.position = position;
				// Same words with a longer prefix: split them again.
				notifyItemRangeChanged(0, getItemCount());
			}
		});
	}

	public class ViewHolder extends RecyclerView.ViewHolder {
		private final Button btn;
		private final int padding;
		private final int tabPadding;
		private final int typedColor;
		private String item = "";

		public ViewHolder(@NonNull View itemView) {
			super(itemView);
			btn = itemView.findViewById(R.id.btn);
			padding = btn.getPaddingStart();
			tabPadding = Math.round(10 * btn.getResources()
					.getDisplayMetrics().density);
			btn.setTypeface(ShaderEditorApp.preferences.getFont());
			typedColor = MaterialColors.getColor(btn,
					androidx.appcompat.R.attr.colorPrimary);
			btn.setOnClickListener((v) -> {
				onInsertListener.onChoose(item, prefixLength());
			});
			if (saysInsert) {
				ViewCompat.replaceAccessibilityAction(btn,
						AccessibilityNodeInfoCompat.AccessibilityActionCompat
								.ACTION_CLICK, btn.getContext().getString(
								R.string.a11y_insert), null);
			}
			itemView.setOnTouchListener(new View.OnTouchListener() {
				@SuppressLint("ClickableViewAccessibility")
				@Override
				public boolean onTouch(View v, MotionEvent event) {
					return btn.onTouchEvent(event); // The FrameLayout always forwards
				}
			});
		}

		public void update(String item) {
			this.item = item;
			boolean tab = TAB.equals(item);
			if (btn instanceof MaterialButton key) {
				// The same cell as the other keys: 44 dp, the icon in the
				// row's text colour.
				key.setIconResource(tab ? R.drawable.ic_keyboard_tab : 0);
				int side = tab ? tabPadding : padding;
				key.setPaddingRelative(side, key.getPaddingTop(), side,
						key.getPaddingBottom());
			}
			if (tab) {
				btn.setText(null);
				btn.setContentDescription(btn.getContext().getString(
						R.string.insert_tab));
				btn.setOnLongClickListener(null);
				btn.setLongClickable(false);
				ViewCompat.replaceAccessibilityAction(btn,
						AccessibilityNodeInfoCompat.AccessibilityActionCompat
								.ACTION_LONG_CLICK, null, null);
				return;
			}
			// The typed prefix is marked in the accent, the rest keeps the
			// row's text colour; one face, so both parts share a baseline.
			SpannableString text = new SpannableString(item);
			text.setSpan(new ForegroundColorSpan(typedColor), 0, prefixLength(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			btn.setText(text);
			// A word with docs looks itself up on a long press, which
			// TalkBack offers as Look up; a function without its
			// parenthesis; the key "(" alone is no function.
			boolean function = item.length() > 1 && item.endsWith("(");
			String word = function
					? item.substring(0, item.length() - 1)
					: item;
			btn.setContentDescription(function
					? btn.getContext().getString(
					R.string.a11y_completion_function, word)
					: null);
			if (lookUp.hasDocs(word)) {
				btn.setOnLongClickListener(v -> {
					lookUp.lookUp(word);
					return true;
				});
				ViewCompat.replaceAccessibilityAction(btn,
						AccessibilityNodeInfoCompat.AccessibilityActionCompat
								.ACTION_LONG_CLICK,
						btn.getContext().getString(R.string.look_up), null);
			} else {
				btn.setOnLongClickListener(null);
				btn.setLongClickable(false);
				ViewCompat.replaceAccessibilityAction(btn,
						AccessibilityNodeInfoCompat.AccessibilityActionCompat
								.ACTION_LONG_CLICK, null, null);
			}
		}

		private int prefixLength() {
			return Math.min(position, item.length());
		}
	}
}
