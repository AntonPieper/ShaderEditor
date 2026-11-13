package de.markusfisch.android.shadereditor.activity.managers;

import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.opengl.PictureLimit;
import de.markusfisch.android.shadereditor.widget.TouchThruDrawerLayout;

public class UIManager {
	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ExtraKeysManager extraKeysManager;
	private final ShaderViewManager shaderViewManager;
	private final Toolbar toolbar;
	private final TouchThruDrawerLayout drawerLayout;
	private final Runnable showSubtitle = this::showSubtitle;

	// The toolbar's title without the unsaved mark.
	@NonNull
	private CharSequence title = "";
	private final SecondLine secondLine = new SecondLine();

	public final ActionBarDrawerToggle drawerToggle;

	public UIManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editorFragment,
			@NonNull ExtraKeysManager extraKeysManager,
			@NonNull ShaderViewManager shaderViewManager) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.extraKeysManager = extraKeysManager;
		this.shaderViewManager = shaderViewManager;

		toolbar = activity.findViewById(R.id.toolbar);
		activity.setSupportActionBar(toolbar);

		drawerLayout = activity.findViewById(R.id.drawer_layout);
		drawerToggle = new ActionBarDrawerToggle(
				activity, drawerLayout, toolbar,
				R.string.drawer_open, R.string.drawer_close);
		drawerToggle.setDrawerIndicatorEnabled(true);
		drawerLayout.addDrawerListener(drawerToggle);
	}

	public void setupToolbar(View.OnClickListener menuClickListener,
			View.OnClickListener runClickListener,
			View.OnClickListener toggleCodeClickListener,
			View.OnClickListener showErrorClickListener) {
		View menuBtn = toolbar.findViewById(R.id.menu_btn);
		ViewCompat.setTooltipText(menuBtn, activity.getText(R.string.menu_btn));
		menuBtn.setOnClickListener(menuClickListener);

		View runCode = toolbar.findViewById(R.id.run_code);
		ViewCompat.setTooltipText(runCode, activity.getText(R.string.run_code));
		runCode.setOnClickListener(runClickListener);

		View toggleCode = toolbar.findViewById(R.id.toggle_code);
		ViewCompat.setTooltipText(toggleCode, activity.getText(R.string.toggle_code));
		toggleCode.setOnClickListener(toggleCodeClickListener);

		View showErrors = toolbar.findViewById(R.id.show_errors);
		ViewCompat.setTooltipText(showErrors, activity.getText(R.string.show_errors));
		showErrors.setOnClickListener(showErrorClickListener);
	}

	public void updateUiToPreferences() {
		boolean runInBackground =
				ShaderEditorApp.preferences.doesRunInBackground();
		shaderViewManager.setVisibility(runInBackground);
		toolbar.findViewById(R.id.toggle_code).setVisibility(
				runInBackground ? View.VISIBLE : View.GONE);
		if (!runInBackground && !editorFragment.isCodeVisible()) {
			toggleCodeVisibility();
		}
		toolbar.findViewById(R.id.run_code).setVisibility(
				!ShaderEditorApp.preferences.doesRunOnChange()
						? View.VISIBLE
						: View.GONE);
		extraKeysManager.updateVisibility();
		editorFragment.setShowLineNumbers(
				ShaderEditorApp.preferences.showLineNumbers());
		editorFragment.updateHighlighting();
		limitPicture(false);
		activity.invalidateOptionsMenu();
	}

	public void toggleCodeVisibility() {
		boolean isVisible = editorFragment.toggleCode();
		drawerLayout.setTouchThru(isVisible);
		extraKeysManager.setVisible(!isVisible &&
				ShaderEditorApp.preferences.showExtraKeys());
		limitPicture(Settings.Global.getFloat(activity.getContentResolver(),
				Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f);
	}

	@NonNull
	public CharSequence getToolbarTitle() {
		return title;
	}

	/**
	 * Show the title, with a dot before it while there are unsaved
	 * changes: before, so that an ellipsis never cuts it off.
	 */
	public void setToolbarTitle(@Nullable CharSequence title,
			@Nullable CharSequence subtitle, boolean unsaved) {
		if (title == null) {
			title = "";
		}
		if (!TextUtils.equals(title, this.title)) {
			secondLine.setFrameRate(null);
		}
		this.title = title;
		secondLine.setOwn(subtitle);
		toolbar.setTitle(unsaved
				? activity.getString(R.string.unsaved_title, title)
				: title);
		TextView titleView = findTitleView();
		if (titleView != null) {
			titleView.setContentDescription(unsaved
					? title + ", " + activity.getString(R.string.unsaved_changes)
					: null);
		}
		showSubtitle();
	}

	/** Show text in the second line for a while instead of what is there. */
	public void showNotice(@NonNull CharSequence text, long millis) {
		secondLine.showNotice(text, SystemClock.uptimeMillis() + millis);
		toolbar.removeCallbacks(showSubtitle);
		toolbar.postDelayed(showSubtitle, millis);
		showSubtitle();
	}

	public void closeDrawers() {
		drawerLayout.closeDrawers();
	}

	/** Set the frame rate; it may be called from any thread. */
	public void setFrameRate(@NonNull String frameRate) {
		toolbar.post(() -> {
			secondLine.setFrameRate(frameRate);
			showSubtitle();
		});
	}

	private void showSubtitle() {
		CharSequence line = secondLine.shown(SystemClock.uptimeMillis());
		if (!TextUtils.equals(line, toolbar.getSubtitle())) {
			toolbar.setSubtitle(line);
		}
	}

	// The toolbar keeps its title in a view of its own.
	@Nullable
	private TextView findTitleView() {
		CharSequence shown = toolbar.getTitle();
		for (int i = toolbar.getChildCount(); i-- > 0; ) {
			View child = toolbar.getChildAt(i);
			if (child instanceof TextView textView &&
					TextUtils.equals(textView.getText(), shown)) {
				return textView;
			}
		}
		return null;
	}

	// Code in front of the picture limits its brightness, so that the
	// code can be read.
	private void limitPicture(boolean fade) {
		shaderViewManager.limitPicture(editorFragment.isCodeVisible()
				? ShaderEditorApp.preferences.getPictureCeiling()
				: PictureLimit.NONE, fade);
	}
}
