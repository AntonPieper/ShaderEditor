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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

public class CompletionsAdapter extends ListAdapter<String, CompletionsAdapter.ViewHolder> {
	@FunctionalInterface
	public interface OnInsertListener {
		void onInsert(@NonNull CharSequence sequence);
	}

	private static final DiffUtil.ItemCallback<String> DIFF_CALLBACK = new StringDiffer();

	private final OnInsertListener onInsertListener;

	private int position = 0;

	public CompletionsAdapter(OnInsertListener onInsertListener) {
		super(DIFF_CALLBACK);
		this.onInsertListener = onInsertListener;
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
		private final int typedColor;
		private String item = "";

		public ViewHolder(@NonNull View itemView) {
			super(itemView);
			btn = itemView.findViewById(R.id.btn);
			btn.setTypeface(ShaderEditorApp.preferences.getFont());
			typedColor = MaterialColors.getColor(btn,
					androidx.appcompat.R.attr.colorPrimary);
			btn.setOnClickListener((v) -> {
				onInsertListener.onInsert(item.substring(prefixLength()));
			});
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
			// The typed prefix is marked in the accent, the rest keeps the
			// row's text colour; one face, so both parts share a baseline.
			SpannableString text = new SpannableString(item);
			text.setSpan(new ForegroundColorSpan(typedColor), 0, prefixLength(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			btn.setText(text);
		}

		private int prefixLength() {
			return Math.min(position, item.length());
		}
	}
}
