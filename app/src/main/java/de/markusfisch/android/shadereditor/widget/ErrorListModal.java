package de.markusfisch.android.shadereditor.widget;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
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
import de.markusfisch.android.shadereditor.fragment.ListedErrors;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * Lists the errors its host keeps. The system makes the sheet again
 * after a recreate, so it holds no errors of its own: it shows what the
 * host knows of the current build (ListedErrors), waits hidden while
 * that is not known yet, and goes only when the build reported none.
 */
public class ErrorListModal extends BottomSheetDialogFragment {
	public static final String TAG = "ErrorListModal";

	/** The fragment that shows the sheet, or else its activity. */
	public interface Host {
		@NonNull
		ListedErrors.State getListedState();

		@NonNull
		List<ProjectDiagnostic> getListedErrors();

		// Whether the picture is the last that worked, not of the errors.
		boolean showsLastPicture();

		void onErrorChosen(@Nullable ProjectPath file, int line);

		/** Return the fixes the row of error in the code offers. */
		@NonNull
		default List<CodeView.Fix> getFixes(
				@NonNull ProjectDiagnostic error) {
			return List.of();
		}

		/** Apply fix index of getFixes(error). */
		default void applyFix(@NonNull ProjectDiagnostic error, int index) {
		}
	}

	@Nullable
	private ErrorAdapter adapter;
	@Nullable
	private View lastPicture;
	// Hidden until the host knows the errors: made again after a
	// recreate, or while a build has not reported yet.
	private boolean waiting;

	public ErrorListModal() {
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		waiting = savedInstanceState != null;
	}

	// Open fully: collapsed, a landscape screen leaves the first row
	// behind the navigation bar.
	@Override
	public void onStart() {
		super.onStart();
		Dialog dialog = getDialog();
		if (dialog instanceof BottomSheetDialog sheet) {
			BottomSheetBehavior<?> behavior = sheet.getBehavior();
			behavior.setSkipCollapsed(true);
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		}
		if (waiting && dialog != null) {
			dialog.hide();
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.error_list_bottom_sheet_content,
				container, false);
		lastPicture = view.findViewById(R.id.last_picture);
		RecyclerView errorList = view.findViewById(R.id.error_list);
		errorList.setLayoutManager(new LinearLayoutManager(requireContext()));
		DividerItemDecoration divider = new DividerItemDecoration(errorList.getContext(),
				DividerItemDecoration.VERTICAL);
		divider.setDrawable(Objects.requireNonNull(ContextCompat.getDrawable(requireContext(),
				R.drawable.divider_with_padding_horizontal)));
		errorList.addItemDecoration(divider);
		adapter = new ErrorAdapter((file, lineNumber) -> {
			Host host = host();
			if (host != null) {
				host.onErrorChosen(file, lineNumber);
			}
			dismiss();
		}, new ErrorAdapter.Fixes() {
			@NonNull
			@Override
			public List<CodeView.Fix> of(@NonNull ProjectDiagnostic error) {
				Host host = host();
				return host != null ? host.getFixes(error) : List.of();
			}

			@Override
			public void apply(@NonNull ProjectDiagnostic error, int index) {
				Host host = host();
				if (host != null) {
					host.applyFix(error, index);
				}
				dismiss();
			}
		});
		errorList.setAdapter(adapter);
		update();
		return view;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		adapter = null;
		lastPicture = null;
	}

	/** Show what the host knows now of the errors of the current build. */
	public void update() {
		Host host = host();
		if (adapter == null || lastPicture == null || host == null) {
			return;
		}
		Dialog dialog = getDialog();
		switch (host.getListedState()) {
			case NONE -> dismissAllowingStateLoss();
			case UNKNOWN -> {
				waiting = true;
				if (dialog != null) {
					dialog.hide();
				}
			}
			case ERRORS -> {
				waiting = false;
				adapter.submitList(host.getListedErrors());
				lastPicture.setVisibility(host.showsLastPicture()
						? View.VISIBLE
						: View.GONE);
				// Before its start, onStart() shows it.
				if (dialog != null && !dialog.isShowing() && getLifecycle()
						.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
					dialog.show();
				}
			}
		}
	}

	@Nullable
	private Host host() {
		Fragment parent = getParentFragment();
		if (parent instanceof Host host) {
			return host;
		}
		return getActivity() instanceof Host host ? host : null;
	}
}
