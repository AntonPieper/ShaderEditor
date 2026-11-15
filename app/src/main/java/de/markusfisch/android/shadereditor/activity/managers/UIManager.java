package de.markusfisch.android.shadereditor.activity.managers;

import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.opengl.PictureLimit;
import de.markusfisch.android.shadereditor.widget.FileNameView;
import de.markusfisch.android.shadereditor.widget.PassesSheet;
import de.markusfisch.android.shadereditor.widget.TouchThruDrawerLayout;

public class UIManager implements PassesSheet.Line {
	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ExtraKeysManager extraKeysManager;
	private final ShaderViewManager shaderViewManager;
	private final Toolbar toolbar;
	private final FileNameView titleView;
	private boolean titleIsFileName;
	private final TextView subtitleView;
	private final Drawable unsavedDot;
	private final View titleColumn;
	private final View quality;
	private final int titleMin;
	private boolean frameRateFits = true;
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
		// The toolbar's own title is replaced by titleView.
		Objects.requireNonNull(activity.getSupportActionBar())
				.setDisplayShowTitleEnabled(false);
		titleView = toolbar.findViewById(R.id.toolbar_title);
		subtitleView = toolbar.findViewById(R.id.toolbar_subtitle);
		// Lay out where the toolbar lays out its own title, to the pixel.
		var titleParams = (ViewGroup.MarginLayoutParams)
				((View) titleView.getParent()).getLayoutParams();
		titleParams.setMarginStart(toolbar.getTitleMarginStart());
		titleParams.topMargin = toolbar.getTitleMarginTop();
		titleParams.bottomMargin = toolbar.getTitleMarginBottom();
		unsavedDot = Objects.requireNonNull(AppCompatResources.getDrawable(
				activity, R.drawable.unsaved_dot)).mutate();
		unsavedDot.setTintList(titleView.getTextColors());
		titleColumn = (View) titleView.getParent();
		quality = toolbar.findViewById(R.id.quality);
		titleMin = activity.getResources().getDimensionPixelSize(
				R.dimen.toolbar_title_min);
		toolbar.addOnLayoutChangeListener((v, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> fitFrameRate());
		// The one place that limits the picture for the code over it, for
		// every way the code comes to be shown or not: the start, a
		// restore, the code button, nothing open and something opened.
		// Code over a bright picture cannot be read, so the picture is
		// dimmed at once when the code appears and only fades back.
		editorFragment.setOnCodeShownListener(shown -> limitPicture(
				!shown && Settings.Global.getFloat(
						activity.getContentResolver(),
						Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f));

		drawerLayout = activity.findViewById(R.id.drawer_layout);
		drawerToggle = new ActionBarDrawerToggle(
				activity, drawerLayout, toolbar,
				R.string.drawer_open, R.string.drawer_close);
		drawerToggle.setDrawerIndicatorEnabled(true);
		drawerLayout.addDrawerListener(drawerToggle);

		OnBackPressedCallback closeDrawer = new OnBackPressedCallback(false) {
			@Override
			public void handleOnBackPressed() {
				drawerLayout.closeDrawers();
			}
		};
		activity.getOnBackPressedDispatcher().addCallback(activity, closeDrawer);
		drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
			@Override
			public void onDrawerOpened(@NonNull View drawerView) {
				closeDrawer.setEnabled(true);
			}

			@Override
			public void onDrawerClosed(@NonNull View drawerView) {
				closeDrawer.setEnabled(false);
			}
		});
		// A drawer that is restored open tells no listener.
		drawerLayout.post(() -> closeDrawer.setEnabled(
				drawerLayout.isDrawerOpen(GravityCompat.START)));
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
	}

	@NonNull
	public CharSequence getToolbarTitle() {
		return title;
	}

	/**
	 * Show the title, with a dot after it while there are unsaved
	 * changes. The dot has a slot of its own: a long name is shortened
	 * before it, and the name's start never moves.
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
		showFrameRate();
		// The name never moves: the dot is an end drawable, after it.
		if (titleIsFileName) {
			titleView.setName(title);
		} else {
			titleView.setText(title);
		}
		titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(
				null, null, unsaved ? unsavedDot : null, null);
		titleView.setContentDescription(unsaved
				? title + ", " + activity.getString(R.string.unsaved_changes)
				: null);
		showSubtitle();
	}

	/**
	 * Take whether the title is a file's name, which is then cut so that
	 * its ending stays, as "A very long sketch na….frag"; a shader of
	 * the database keeps its title as it is.
	 */
	public void setTitleIsFileName(boolean fileName) {
		titleIsFileName = fileName;
		// A long press shows the whole name; a tap still does what a tap
		// on the toolbar does.
		titleView.setWholeOnLongPress(fileName);
		titleView.setOnClickListener(fileName
				? v -> toolbar.performClick()
				: null);
		titleView.setClickable(fileName);
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

	/**
	 * Set the frame rate, none when null; it may be called from any
	 * thread.
	 */
	public void setFrameRate(@Nullable String frameRate) {
		toolbar.post(() -> {
			secondLine.setFrameRate(frameRate);
			showFrameRate();
			showSubtitle();
		});
	}

	/**
	 * Take whether the chip names a pass shown, so that the frame rate
	 * leads the second line, and whether the picture is paused, said
	 * where the frame rate stands.
	 */
	@Override
	public void setPicture(boolean passShown, boolean paused) {
		secondLine.setRateFirst(passShown);
		// Beside a pass's chip the title gives way last, at its end.
		titleView.setCutsAtEnd(passShown);
		// The frame rate leads then, and must not be what is cut.
		subtitleView.setEllipsize(passShown
				? TextUtils.TruncateAt.END
				: TextUtils.TruncateAt.MIDDLE);
		secondLine.setPaused(paused
				? activity.getString(R.string.passes_paused)
				: null);
		showFrameRate();
		showSubtitle();
	}

	// The frame rate stands in the quality chip only while the title
	// keeps its room; otherwise it is not shown until there is room.
	private void showFrameRate() {
		shaderViewManager.setFrameRate(frameRateFits
				? secondLine.besideQuality()
				: null);
	}

	private void fitFrameRate() {
		CharSequence frameRate = secondLine.besideQuality();
		if (frameRate == null) {
			return;
		}
		int chipWithRate = shaderViewManager.chipWidth(frameRate);
		if (chipWithRate < 0) {
			return;
		}
		// With the unsaved mark always, so that it does not move the rate.
		int titleNeeds = Math.round(titleView.getPaint().measureText(
				title.toString())) + titleView.getCompoundDrawablePadding() +
				unsavedDot.getIntrinsicWidth();
		boolean fits = ToolbarSpace.frameRateFits(
				quality.getRight() - titleColumn.getLeft(), chipWithRate,
				titleNeeds, titleMin);
		if (fits != frameRateFits) {
			frameRateFits = fits;
			toolbar.post(this::showFrameRate);
		}
	}

	private void showSubtitle() {
		CharSequence line = secondLine.shown(SystemClock.uptimeMillis());
		if (!TextUtils.equals(line, subtitleView.getText())) {
			subtitleView.setText(line);
		}
		subtitleView.setVisibility(TextUtils.isEmpty(line)
				? View.GONE
				: View.VISIBLE);
	}

	// Code in front of the picture limits its brightness, so that the
	// code can be read.
	private void limitPicture(boolean fade) {
		shaderViewManager.limitPicture(editorFragment.isCodeShown()
				? ShaderEditorApp.preferences.getPictureCeiling()
				: PictureLimit.NONE, fade);
	}
}
