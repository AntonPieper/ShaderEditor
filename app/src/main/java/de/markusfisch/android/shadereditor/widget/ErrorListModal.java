package de.markusfisch.android.shadereditor.widget;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;
import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.ErrorAdapter;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

public class ErrorListModal extends BottomSheetDialogFragment {
	public static final String TAG = "ErrorListModal";
	@NonNull
	private final ErrorAdapter.OnItemClickListener onItemClickListener;

	@NonNull
	final List<ProjectDiagnostic> errors;
	// Whether the picture is the last that worked, not of these errors.
	private final boolean lastPicture;

	public ErrorListModal(@NonNull List<ProjectDiagnostic> errors,
			@NonNull ErrorAdapter.OnItemClickListener onItemClickListener) {
		this(errors, onItemClickListener, false);
	}

	public ErrorListModal(@NonNull List<ProjectDiagnostic> errors,
			@NonNull ErrorAdapter.OnItemClickListener onItemClickListener,
			boolean lastPicture) {
		this.errors = errors;
		this.lastPicture = lastPicture;
		this.onItemClickListener = (file, lineNumber) -> {
			onItemClickListener.onItemClick(file, lineNumber);
			dismiss();
		};
	}

	// Open fully: collapsed, a landscape screen leaves the first row
	// behind the navigation bar.
	@Override
	public void onStart() {
		super.onStart();
		if (getDialog() instanceof BottomSheetDialog dialog) {
			BottomSheetBehavior<?> behavior = dialog.getBehavior();
			behavior.setSkipCollapsed(true);
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.error_list_bottom_sheet_content,
				container, false);
		view.findViewById(R.id.last_picture).setVisibility(lastPicture
				? View.VISIBLE
				: View.GONE);
		RecyclerView errorList = view.findViewById(R.id.error_list);
		errorList.setLayoutManager(new LinearLayoutManager(requireContext()));
		DividerItemDecoration divider = new DividerItemDecoration(errorList.getContext(),
				DividerItemDecoration.VERTICAL);
		divider.setDrawable(Objects.requireNonNull(ContextCompat.getDrawable(requireContext(),
				R.drawable.divider_with_padding_horizontal)));
		errorList.addItemDecoration(divider);
		ErrorAdapter adapter = new ErrorAdapter(onItemClickListener);
		adapter.submitList(errors);
		errorList.setAdapter(adapter);
		return view;
	}
}