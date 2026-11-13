package de.markusfisch.android.shadereditor.fragment;

import android.view.View;
import android.widget.AbsListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import de.markusfisch.android.shadereditor.activity.AddUniformActivity;

public abstract class AddUniformPageFragment extends Fragment {
	@Override
	public void onResume() {
		super.onResume();
		if (requireActivity() instanceof AddUniformActivity addUniformActivity) {
			addUniformActivity.setSearchListener(this::onSearch);
			var currentSearchQuery = addUniformActivity.getCurrentSearchQuery();
			if (currentSearchQuery != null) {
				onSearch(currentSearchQuery);
			}
		}
	}

	protected abstract void onSearch(@Nullable String query);

	// The pager detaches a page that leaves the screen and attaches it
	// again when it comes back. On attach, AbsListView marks its data as
	// changed and ignores every tap until its next layout, which does
	// not come when the page keeps its size.
	protected static void layoutOnAttach(@NonNull AbsListView listView) {
		listView.addOnAttachStateChangeListener(
				new View.OnAttachStateChangeListener() {
					@Override
					public void onViewAttachedToWindow(@NonNull View v) {
						v.requestLayout();
					}

					@Override
					public void onViewDetachedFromWindow(@NonNull View v) {
					}
				});
	}
}
