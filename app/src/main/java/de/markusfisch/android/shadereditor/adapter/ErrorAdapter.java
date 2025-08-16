package de.markusfisch.android.shadereditor.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.engine.error.EngineError;

public class ErrorAdapter extends ListAdapter<EngineError.SourceLocation, ErrorAdapter.ViewHolder> {
	@FunctionalInterface
	public interface OnItemClickListener {
		void onItemClick(int lineNumber);
	}
	private static final DiffUtil.ItemCallback<EngineError.SourceLocation> DIFF_CALLBACK =
			new DiffUtil.ItemCallback<>() {
				@Override
				public boolean areItemsTheSame(@NonNull EngineError.SourceLocation oldItem,
						@NonNull EngineError.SourceLocation newItem) {
					return oldItem.line() == newItem.line() &&
							oldItem.message().equals(newItem.message());
				}

				@Override
				public boolean areContentsTheSame(@NonNull EngineError.SourceLocation oldItem,
						@NonNull EngineError.SourceLocation newItem) {
					return oldItem.equals(newItem);
				}
			};
	@NonNull
	private final OnItemClickListener listener;

	public ErrorAdapter(@NonNull OnItemClickListener listener) {
		super(DIFF_CALLBACK);
		this.listener = listener;
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new ViewHolder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.error_item, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		EngineError.SourceLocation error = getItem(position);
		if (error != null) {
			holder.update(error, listener);
		}
	}

	public static class ViewHolder extends RecyclerView.ViewHolder {
		TextView errorLine;
		TextView errorMessage;

		public ViewHolder(@NonNull View itemView) {
			super(itemView);
			errorLine = itemView.findViewById(R.id.error_line);
			errorMessage = itemView.findViewById(R.id.error_message);
		}

		public void update(@NonNull EngineError.SourceLocation error,
				OnItemClickListener listener) {
			if (error.line() > 0) {
				errorLine.setText(String.format(Locale.getDefault(), "%d:", error.line()));
				errorLine.setVisibility(View.VISIBLE);
				itemView.setOnClickListener(v -> listener.onItemClick(error.line()));
				itemView.setClickable(true);
			} else {
				errorLine.setVisibility(View.GONE);
				itemView.setOnClickListener(null);
				itemView.setClickable(false);
			}
			errorMessage.setText(error.message());
		}
	}
}