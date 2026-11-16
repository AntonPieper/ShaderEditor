package de.markusfisch.android.shadereditor.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.widget.CodeView;

public class ErrorAdapter extends ListAdapter<ProjectDiagnostic, ErrorAdapter.ViewHolder> {
	@FunctionalInterface
	public interface OnItemClickListener {
		void onItemClick(@Nullable ProjectPath file, int lineNumber);
	}

	/** The fixes the row of an error in the code offers. */
	public interface Fixes {
		@NonNull
		List<CodeView.Fix> of(@NonNull ProjectDiagnostic error);

		void apply(@NonNull ProjectDiagnostic error, int index);
	}

	@NonNull
	private final OnItemClickListener listener;
	@NonNull
	private final Fixes fixes;

	public ErrorAdapter(@NonNull OnItemClickListener listener,
			@NonNull Fixes fixes) {
		super(DIFF_CALLBACK);
		this.listener = listener;
		this.fixes = fixes;
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
			holder.update(error, listener, fixes);
		}
	}

	public static class ViewHolder extends RecyclerView.ViewHolder {
		ImageView errorSeverity;
		TextView errorLine;
		TextView errorMessage;
		View fixRow;
		Button[] fixButtons;
		// The accessibility actions of the fixes shown.
		final List<Integer> actions = new ArrayList<>();

		public ViewHolder(@NonNull View itemView) {
			super(itemView);
			errorSeverity = itemView.findViewById(R.id.error_severity);
			errorLine = itemView.findViewById(R.id.error_line);
			errorMessage = itemView.findViewById(R.id.error_message);
			fixRow = itemView.findViewById(R.id.error_fixes);
			fixButtons = new Button[]{
					itemView.findViewById(R.id.error_fix_0),
					itemView.findViewById(R.id.error_fix_1)};
		}

		public void update(@NonNull ProjectDiagnostic error,
				ErrorAdapter.OnItemClickListener listener,
				@NonNull Fixes fixes) {
			boolean warning = error.severity() == ProjectDiagnostic.Severity.WARNING;
			errorSeverity.setImageResource(warning
					? R.drawable.ic_warning
					: R.drawable.ic_error);
			errorSeverity.setContentDescription(itemView.getContext().getString(
					warning ? R.string.warning : R.string.error));
			String location = error.location();
			if (!location.isEmpty()) {
				errorLine.setText(String.format(Locale.getDefault(), "%s: ", location));
				errorLine.setVisibility(View.VISIBLE);
			} else {
				errorLine.setVisibility(View.GONE);
			}

			errorMessage.setText(error.message());

			itemView.setOnClickListener(v -> listener.onItemClick(
					error.file(), error.line()));
			showFixes(error, fixes);
		}

		// The fixes as the code's row offers them: its buttons, and for
		// TalkBack the row's actions, said at the end of the row.
		private void showFixes(@NonNull ProjectDiagnostic error,
				@NonNull Fixes fixes) {
			for (int action : actions) {
				ViewCompat.removeAccessibilityAction(itemView, action);
			}
			actions.clear();
			List<CodeView.Fix> offered = fixes.of(error);
			List<String> spoken = new ArrayList<>();
			for (int i = 0; i < fixButtons.length; ++i) {
				Button button = fixButtons[i];
				if (i >= offered.size()) {
					button.setVisibility(View.GONE);
					continue;
				}
				CodeView.Fix fix = offered.get(i);
				int index = i;
				button.setVisibility(View.VISIBLE);
				button.setText(fix.labels().get(0));
				button.setOnClickListener(v -> fixes.apply(error, index));
				spoken.add(fix.spoken());
				actions.add(ViewCompat.addAccessibilityAction(itemView,
						fix.spoken(), (v, arguments) -> {
							fixes.apply(error, index);
							return true;
						}));
			}
			fixRow.setVisibility(offered.isEmpty() ? View.GONE : View.VISIBLE);
			itemView.setContentDescription(spoken.isEmpty()
					? null
					: TextUtils.concat(errorSeverity.getContentDescription(),
					" ", errorLine.getVisibility() == View.VISIBLE
							? errorLine.getText()
							: "", errorMessage.getText(), " ",
					itemView.getContext().getString(
							R.string.a11y_fix_available,
							TextUtils.join(", ", spoken))));
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