package de.markusfisch.android.shadereditor.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

public class ErrorAdapter extends ListAdapter<ProjectDiagnostic, ErrorAdapter.ViewHolder> {
	@FunctionalInterface
	public interface OnItemClickListener {
		void onItemClick(@Nullable ProjectPath file, int lineNumber);
	}

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
		ProjectDiagnostic error = getItem(position);
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

		public void update(@NonNull ProjectDiagnostic error, ErrorAdapter.OnItemClickListener listener) {
			String location = error.location();
			if (!location.isEmpty()) {
				errorLine.setText(String.format(Locale.getDefault(), "%s: ", location));
			} else {
				errorLine.setVisibility(View.GONE);
			}

			errorMessage.setText(error.message());

			itemView.setOnClickListener(v -> listener.onItemClick(
					error.file(), error.line()));
		}
	}

	private static final DiffUtil.ItemCallback<ProjectDiagnostic> DIFF_CALLBACK =
			new DiffUtil.ItemCallback<ProjectDiagnostic>() {
				@Override
				public boolean areItemsTheSame(@NonNull ProjectDiagnostic oldItem,
						@NonNull ProjectDiagnostic newItem) {
					// Implement logic to check if items are the same
					return oldItem.equals(newItem);
				}

				@Override
				public boolean areContentsTheSame(@NonNull ProjectDiagnostic oldItem,
						@NonNull ProjectDiagnostic newItem) {
					// Implement logic to check if item contents are the same
					return oldItem.equals(newItem);
				}
			};
}