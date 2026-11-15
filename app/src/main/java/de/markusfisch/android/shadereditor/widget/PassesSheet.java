package de.markusfisch.android.shadereditor.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.util.Consumer;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.animation.PathInterpolatorCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.sidesheet.SideSheetBehavior;
import com.google.android.material.sidesheet.SideSheetCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.NameCut;
import de.markusfisch.android.shadereditor.opengl.Fit;
import de.markusfisch.android.shadereditor.opengl.PassFacts;
import de.markusfisch.android.shadereditor.opengl.PassPicture;
import de.markusfisch.android.shadereditor.opengl.RunningPasses;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The passes sheet, opened from the frame-rate chip where the shaders
 * are files: what the running shader is made of, and its controls.
 * Pause, Step and Restart act on the picture; quality is one tap. While
 * the sheet is open the code is set aside, so that the picture is seen
 * as it is.
 *
 * <p>What it shows is one {@link State}, changed only through
 * {@link #set}, and drawn in one place, {@link #render}. It hears the
 * engine only while it is open. Call it on the main thread.
 */
public final class PassesSheet {
	/** The picture the sheet acts on, and the chip it opens from. */
	public interface Engine {
		/** Run opener on a tap on the chip instead of its list. */
		void setChipOpener(@Nullable Runnable opener);

		/** The passes of the build that draws, or none; any thread. */
		@NonNull
		RunningPasses passes();

		/** Tell passes, on any thread, what draws from now on. */
		void setPassesListener(@Nullable Consumer<RunningPasses> passes);

		/** Let watcher hear the passes' pictures and facts. */
		void watchPasses(@Nullable ShaderEngine.PassWatcher watcher);

		void setPaused(boolean paused);

		void step();

		void restart();

		/** Show the pass of file full size, the output for null. */
		void showPass(@Nullable ProjectPath file);

		void setFit(@Nullable Fit fit);

		/** Choose quality as if it were chosen in the chip. */
		void chooseQuality(float quality);

		float getQuality();

		/** The most frames a second drawn, 0 for as fast as the screen. */
		int getFrameRateLimit();
	}

	/** The code over the picture. */
	public interface Code {
		/** Set the code aside while the sheet is open, or give it back. */
		void setAside(boolean aside, boolean animate);
	}

	/** The toolbar's second line. */
	public interface Line {
		/**
		 * Take whether a pass is shown, so that the frame rate leads the
		 * line, and whether the picture is paused.
		 */
		void setPicture(boolean passShown, boolean paused);
	}

	/** Where the sheet goes to the code and to what is kept of it. */
	public interface Host {
		/**
		 * Open file of what runs at line, from 1, or where its caret is
		 * for 0, with a way back to the line the editor is on.
		 */
		void open(@NonNull ProjectPath file, int line);

		/**
		 * Draw at most limit frames a second, as fast as the screen for
		 * 0, and keep it with what runs.
		 */
		void keepFrameRateLimit(int limit);
	}

	// What the frame-rate limit offers, 0 for none.
	private static final int[] LIMITS = {0, 60, 30, 15, 5};

	/**
	 * What the sheet shows.
	 *
	 * @param open      whether it is open
	 * @param available whether something that can run is open, so that
	 *                  the chip is in the toolbar
	 * @param paused    whether the picture is paused
	 * @param passes    what draws now
	 * @param shown     the pass shown full size, null for the output
	 * @param quality   the quality chosen
	 * @param pictures  the latest small picture of each pass that draws
	 * @param facts     the latest facts of each pass
	 * @param fit       the range the pass shown is fitted from, or null
	 * @param limit     the most frames a second, 0 for none
	 * @param frame     the number of the frame drawn last
	 */
	record State(boolean open, boolean available, boolean paused,
			@NonNull RunningPasses passes, @Nullable ProjectPath shown,
			float quality, @NonNull Map<ProjectPath, Bitmap> pictures,
			@NonNull Map<ProjectPath, PassFacts> facts, @Nullable Fit fit,
			int limit, int frame) {
		static final State CLOSED = new State(false, false, false,
				RunningPasses.NONE, null, 1f, Map.of(), Map.of(), null, 0, 0);

		boolean runs() {
			return !passes.rows().isEmpty();
		}

		// The row selected: the pass shown, else the output.
		int selected() {
			var rows = passes.rows();
			for (int r = 0; r < rows.size(); ++r) {
				var row = rows.get(r);
				if (shown != null ? row.file().equals(shown) : row.output()) {
					return r;
				}
			}
			return rows.size() - 1;
		}

		State withOpen(boolean open) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withAvailable(boolean available) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withPaused(boolean paused) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withPasses(@NonNull RunningPasses passes) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withShown(@Nullable ProjectPath shown) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withQuality(float quality) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withPictures(@NonNull Map<ProjectPath, Bitmap> pictures) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withFacts(@NonNull Map<ProjectPath, PassFacts> facts) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withFit(@Nullable Fit fit) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withLimit(int limit) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}

		State withFrame(int frame) {
			return new State(open, available, paused, passes, shown, quality,
					pictures, facts, fit, limit, frame);
		}
	}

	private final AppCompatActivity activity;
	private final Engine view;
	private final Code editor;
	private final Line ui;
	private final Host files;
	private final PassFactLine.Words words;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final View sheet;
	// A bottom sheet, or in landscape a side sheet under the toolbar.
	@Nullable
	private final BottomSheetBehavior<View> behavior;
	@Nullable
	private final SideSheetBehavior<View> side;
	private final OnBackPressedCallback back;
	private final MaterialButton pause;
	private final View step;
	private final View restart;
	private final TextView frame;
	private final Chip[] qualities;
	private final Chip limit;
	private final float[] qualityValues;
	private final View divider;
	private final View scroll;
	private final PassList list;
	private final TextView key;
	@NonNull
	private State state = State.CLOSED;
	private final View qualityChip;
	private final Chip shownChip;
	private final FileNameView title;
	private final TextView subtitle;
	// The room for the name of the pass shown in its chip, which gives
	// way in a crowded toolbar before the title does.
	private float nameRoom;
	// What hears the passes, while the sheet is open or a pass is
	// shown, and what watches their pictures, while it is open; each
	// drops what it heard after it was replaced.
	// Whether even the selected row's lines need more lanes than there
	// are, so that what it reads is listed under it instead.
	private boolean listed;
	// Material's standard easing; made with the sheet, as the class is
	// loaded without Android too.
	private final Interpolator standard =
			PathInterpolatorCompat.create(.2f, 0f, 0f, 1f);
	// Fit opening or closing its range, while it does.
	@Nullable
	private ValueAnimator fitting;
	// Whether the rail shows only the selected row's lines, so that
	// another selection lays the rows out again.
	private boolean collapsed;
	@Nullable
	private Object hearing;
	@Nullable
	private Object watching;

	public PassesSheet(@NonNull AppCompatActivity activity,
			@NonNull Engine view, @NonNull Code editor,
			@NonNull Line ui, @NonNull Host files) {
		this.activity = activity;
		this.view = view;
		this.editor = editor;
		this.ui = ui;
		this.files = files;
		words = words(activity);
		ViewGroup coordinator = activity.findViewById(R.id.main_coordinator);
		sheet = LayoutInflater.from(activity).inflate(R.layout.sheet_passes,
				coordinator, false);
		coordinator.addView(sheet);
		if (activity.getResources().getConfiguration().orientation ==
				Configuration.ORIENTATION_LANDSCAPE) {
			behavior = null;
			side = new SideSheetBehavior<>();
			var params = (CoordinatorLayout.LayoutParams) sheet.getLayoutParams();
			params.width = Math.round(412 *
					activity.getResources().getDisplayMetrics().density);
			params.height = ViewGroup.LayoutParams.MATCH_PARENT;
			params.gravity = Gravity.END;
			params.setBehavior(side);
			var shape = new MaterialShapeDrawable(ShapeAppearanceModel.builder()
					.setTopLeftCorner(CornerFamily.ROUNDED, activity.getResources()
							.getDimension(R.dimen.se_corner_container))
					.build());
			shape.setFillColor(ColorStateList.valueOf(MaterialColors.getColor(
					sheet, com.google.android.material.R.attr
							.colorSurfaceContainerLow)));
			sheet.setBackground(shape);
			sheet.findViewById(R.id.passes_handle).setVisibility(View.GONE);
			side.setState(SideSheetBehavior.STATE_HIDDEN);
			side.addCallback(new SideSheetCallback() {
				@Override
				public void onStateChanged(@NonNull View sheet, int newState) {
					if (newState == SideSheetBehavior.STATE_HIDDEN && state.open) {
						close();
					}
				}

				@Override
				public void onSlide(@NonNull View sheet, float slideOffset) {
				}
			});
		} else {
			side = null;
			behavior = BottomSheetBehavior.from(sheet);
			behavior.setState(BottomSheetBehavior.STATE_HIDDEN);
			behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
				@Override
				public void onStateChanged(@NonNull View bottomSheet, int newState) {
					// Dragged away.
					if (newState == BottomSheetBehavior.STATE_HIDDEN && state.open) {
						close();
					}
				}

				@Override
				public void onSlide(@NonNull View bottomSheet, float slideOffset) {
				}
			});
		}
		back = new OnBackPressedCallback(false) {
			@Override
			public void handleOnBackPressed() {
				close();
			}
		};
		activity.getOnBackPressedDispatcher().addCallback(activity, back);

		pause = sheet.findViewById(R.id.passes_pause);
		pause.setOnClickListener(v -> setPaused(!state.paused));
		step = sheet.findViewById(R.id.passes_step);
		step.setOnClickListener(v -> {
			view.step();
			// A light tick, as the system's touch feedback allows.
			v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
		});
		restart = sheet.findViewById(R.id.passes_restart);
		frame = sheet.findViewById(R.id.passes_frame);
		restart.setOnClickListener(v -> {
			view.restart();
			lightFirstFrames();
		});
		ViewCompat.setTooltipText(step, step.getContentDescription());
		ViewCompat.setTooltipText(restart, restart.getContentDescription());
		limit = sheet.findViewById(R.id.passes_limit);
		limit.setOnClickListener(this::chooseLimit);
		limit.setOnCloseIconClickListener(this::chooseLimit);
		limit.setCloseIconContentDescription(activity.getText(
				R.string.passes_limit_menu));

		var names = activity.getResources().getStringArray(R.array.quality_names);
		var values = activity.getResources().getStringArray(R.array.quality_values);
		qualities = new Chip[names.length];
		qualityValues = new float[values.length];
		ChipGroup row = sheet.findViewById(R.id.passes_quality);
		var inflater = LayoutInflater.from(activity);
		for (int i = 0; i < names.length; ++i) {
			float quality = Float.parseFloat(values[i]);
			qualityValues[i] = quality;
			Chip chip = (Chip) inflater.inflate(R.layout.chip_quality, row,
					false);
			chip.setText(names[i]);
			// 48 dp wide, or as wide as its label with the largest text,
			// so that the gaps between them are even.
			float dp = activity.getResources().getDisplayMetrics().density;
			float label = chip.getPaint().measureText(names[i]);
			float pad = Math.max(4 * dp, (48 * dp - label) / 2);
			chip.setChipStartPadding(pad);
			chip.setChipEndPadding(pad);
			chip.setContentDescription(activity.getString(
					R.string.passes_quality, names[i]));
			chip.setOnClickListener(v -> chooseQuality(quality));
			row.addView(chip);
			qualities[i] = chip;
		}
		// Spread over the width while they fit on one line; otherwise,
		// as with the largest text, four to a line, sharing its width.
		row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
				spreadQualities(row));
		divider = sheet.findViewById(R.id.passes_divider);
		scroll = sheet.findViewById(R.id.passes_scroll);
		list = sheet.findViewById(R.id.passes_list);
		key = sheet.findViewById(R.id.passes_key);
		// In the frame-rate chip's place while a pass is shown.
		Toolbar toolbar = activity.findViewById(R.id.toolbar);
		qualityChip = toolbar.findViewById(R.id.quality);
		shownChip = (Chip) inflater.inflate(R.layout.chip_shown_pass, toolbar,
				false);
		var chipParams = new Toolbar.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.END | Gravity.CENTER_VERTICAL);
		chipParams.setMarginEnd(((ViewGroup.MarginLayoutParams)
				qualityChip.getLayoutParams()).getMarginEnd());
		toolbar.addView(shownChip, toolbar.indexOfChild(qualityChip) + 1,
				chipParams);
		title = toolbar.findViewById(R.id.toolbar_title);
		subtitle = toolbar.findViewById(R.id.toolbar_subtitle);
		nameRoom = 120 * activity.getResources().getDisplayMetrics().density;
		toolbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
				fitShownChip());
		shownChip.setOnClickListener(v -> toggle());
		shownChip.setOnCloseIconClickListener(v -> show(null));
		shownChip.setCloseIconContentDescription(activity.getText(
				R.string.passes_show_output));
		view.setChipOpener(this::toggle);
		render(state);
		renderRows();
	}

	/**
	 * Take whether something that can run is open: the chip is in the
	 * toolbar only then, and without it the sheet closes and the output
	 * is shown again.
	 */
	public void setAvailable(boolean available) {
		if (!available) {
			close();
			if (state.shown != null) {
				show(null);
			}
		}
		set(state.withAvailable(available));
	}

	/** Open the sheet, or close it when it is open. */
	public void toggle() {
		if (state.open) {
			close();
		} else {
			open();
		}
	}

	/** Close the sheet; nothing of it is watched any more. */
	public void close() {
		if (state.open) {
			// What it showed stays while it slides away.
			set(state.withOpen(false));
		}
	}

	/** The screen stops: the sheet closes. */
	public void onStop() {
		close();
	}

	public void destroy() {
		close();
		set(state.withShown(null));
		view.setPassesListener(null);
		handler.removeCallbacksAndMessages(null);
		view.setChipOpener(null);
	}

	private void open() {
		if (behavior != null) {
			behavior.setMaxHeight(Math.round(
					((View) sheet.getParent()).getHeight() * .7f));
		} else {
			var params = (ViewGroup.MarginLayoutParams) sheet.getLayoutParams();
			params.topMargin = activity.findViewById(R.id.toolbar).getBottom();
			sheet.setLayoutParams(params);
		}
		set(state.withOpen(true).withPasses(view.passes())
				.withQuality(view.getQuality())
				.withLimit(view.getFrameRateLimit()));
	}

	// The limits, in a menu at the chip.
	private void chooseLimit(@NonNull View anchor) {
		var menu = new PopupMenu(activity, anchor);
		for (int i = 0; i < LIMITS.length; ++i) {
			menu.getMenu().add(Menu.NONE, i, i, LIMITS[i] == 0
					? activity.getString(R.string.passes_limit_none)
					: String.valueOf(LIMITS[i]));
		}
		menu.setOnMenuItemClickListener(item -> {
			int chosen = LIMITS[item.getItemId()];
			files.keepFrameRateLimit(chosen);
			set(state.withLimit(chosen));
			return true;
		});
		menu.show();
	}

	/**
	 * Show the pass of file full size, the output for null, as a tap on
	 * its row does, also from outside the sheet.
	 */
	public void show(@Nullable ProjectPath file) {
		view.showPass(file);
		fit(null);
		set(state.withShown(file));
	}

	// Map the pass shown from range of facts to black and white, or not
	// for null; one range for red, green and blue.
	private void fit(@Nullable PassFacts facts) {
		Fit fit = null;
		if (facts != null && PassFactLine.outside(facts)) {
			float low = PassFactLine.low(facts);
			float high = PassFactLine.high(facts);
			if (high > low && !Float.isInfinite(low) &&
					!Float.isInfinite(high)) {
				fit = new Fit(facts.file(), low, high);
			}
		}
		animateFit(state.fit, fit);
		set(state.withFit(fit));
	}

	// Fit opens its range over 200 ms, from 0..1 to the pass's, and
	// closes it the same way, so that it reads as a stretch of the same
	// picture. Only where no frame of the shader is drawn for it: while
	// paused only the frame drawn last is presented again, and while it
	// runs unthrottled it draws every frame anyway; throttled, each step
	// would draw a frame the limit holds back, so it changes at once.
	// A range across 0 also changes at once: it maps 0 to grey, and no
	// range of that kind leaves the picture as it is to start from.
	private void animateFit(@Nullable Fit from, @Nullable Fit to) {
		if (fitting != null) {
			fitting.cancel();
			fitting = null;
		}
		Fit range = to != null ? to : from;
		if (range == null || to != null && from != null ||
				range.low() < 0 || !animates(activity) ||
				(!state.paused && view.getFrameRateLimit() > 0)) {
			view.setFit(to);
			return;
		}
		boolean opens = to != null;
		var animator = ValueAnimator.ofFloat(0f, 1f);
		animator.setDuration(200);
		animator.setInterpolator(standard);
		animator.addUpdateListener(a -> {
			float f = (float) a.getAnimatedValue();
			float t = opens ? f : 1f - f;
			view.setFit(new Fit(range.file(), range.low() * t,
					1f + (range.high() - 1f) * t));
		});
		animator.addListener(new AnimatorListenerAdapter() {
			private boolean cancelled;

			@Override
			public void onAnimationCancel(Animator animation) {
				cancelled = true;
			}

			@Override
			public void onAnimationEnd(Animator animation) {
				if (!cancelled) {
					view.setFit(to);
				}
			}
		});
		fitting = animator;
		animator.start();
	}

	// Restart draws the first frames again: their rows light for 300 ms
	// and fade over 150 ms, or go out at once without animations.
	private void lightFirstFrames() {
		var rows = state.passes.rows();
		int color = MaterialColors.getColor(list,
				androidx.appcompat.R.attr.colorPrimary);
		float width = 2 * activity.getResources().getDisplayMetrics().density;
		for (int r = 0; r < rows.size() && r < list.getChildCount(); ++r) {
			if (rows.get(r).kind() != RunningPasses.Kind.FIRST_FRAME) {
				continue;
			}
			ShapeableImageView picture = list.getChildAt(r).findViewById(
					R.id.pass_picture);
			picture.setStrokeWidth(width);
			picture.setStrokeColor(ColorStateList.valueOf(color));
			Runnable off = () -> picture.setStrokeWidth(0);
			if (!animates(activity)) {
				picture.postDelayed(off, 300);
				continue;
			}
			var fade = ValueAnimator.ofInt(255, 0);
			fade.setStartDelay(300);
			fade.setDuration(150);
			fade.setInterpolator(standard);
			fade.addUpdateListener(a -> picture.setStrokeColor(
					ColorStateList.valueOf(ColorUtils.setAlphaComponent(
							color, (int) a.getAnimatedValue()))));
			fade.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd(Animator animation) {
					off.run();
				}
			});
			fade.start();
		}
	}

	// What draws now: a pass shown that no longer draws is not shown.
	private void heard(@NonNull RunningPasses passes) {
		ProjectPath shown = state.shown;
		if (shown != null) {
			int row = passes.indexOf(shown);
			if (row < 0 || !passes.rows().get(row).drawn()) {
				view.showPass(null);
				fit(null);
				shown = null;
			}
		}
		set(state.withPasses(passes).withShown(shown)
				.withQuality(view.getQuality()));
	}

	// The engine is heard as the state needs: its passes while the
	// sheet is open or a pass is shown, the pictures while it is open.
	// Calls come on the GL thread; one that comes after it was replaced
	// is dropped.
	private void hear() {
		boolean passes = state.open || state.shown != null;
		if (passes != (hearing != null)) {
			Object token = passes ? new Object() : null;
			hearing = token;
			view.setPassesListener(token == null ? null : running ->
					handler.post(() -> {
						if (hearing == token) {
							heard(running);
						}
					}));
		}
		if (state.open != (watching != null)) {
			Object token = state.open ? new Object() : null;
			watching = token;
			view.watchPasses(token == null ? null : new ShaderEngine.PassWatcher() {
				@Override
				public void onPictures(@NonNull List<PassPicture> pictures) {
					// Copied before this returns, off the main thread:
					// the next round fills the pictures again.
					var bitmaps = new HashMap<ProjectPath, Bitmap>();
					for (var picture : pictures) {
						bitmaps.put(picture.file(), opaque(picture));
					}
					handler.post(() -> {
						if (watching == token) {
							set(state.withPictures(bitmaps));
						}
					});
				}

				@Override
				public void onFrame(int frame) {
					handler.post(() -> {
						if (watching == token) {
							set(state.withFrame(frame));
						}
					});
				}

				@Override
				public void onFacts(@NonNull List<PassFacts> facts) {
					var kept = new HashMap<ProjectPath, PassFacts>();
					for (var f : facts) {
						kept.put(f.file(), copy(f));
					}
					handler.post(() -> {
						if (watching == token) {
							set(state.withFacts(kept));
						}
					});
				}
			});
		}
	}

	private void setPaused(boolean paused) {
		view.setPaused(paused);
		set(state.withPaused(paused));
	}

	private void chooseQuality(float quality) {
		view.chooseQuality(quality);
		set(state.withQuality(quality));
	}

	private void set(@NonNull State next) {
		State was = state;
		state = next;
		render(was);
		hear();
	}

	// The one place that shows the state.
	private void render(@NonNull State was) {
		boolean animates = animates(activity);
		if (state.open != was.open) {
			back.setEnabled(state.open);
			editor.setAside(state.open, animates);
			if (behavior != null) {
				behavior.setState(state.open
						? BottomSheetBehavior.STATE_EXPANDED
						: BottomSheetBehavior.STATE_HIDDEN);
			} else if (side != null) {
				side.setState(state.open
						? SideSheetBehavior.STATE_EXPANDED
						: SideSheetBehavior.STATE_HIDDEN);
			}
		}
		boolean runs = state.runs();
		pause.setEnabled(runs);
		int icon = state.paused ? R.drawable.ic_play_arrow : R.drawable.ic_pause;
		if (state.paused != was.paused && animates) {
			// Pause and Run cross over 100 ms.
			pause.animate().cancel();
			pause.animate().alpha(0f).setDuration(50).withEndAction(() -> {
				pause.setIconResource(icon);
				pause.animate().alpha(1f).setDuration(50);
			});
		} else if (state.paused != was.paused || was == state) {
			pause.animate().cancel();
			pause.setAlpha(1f);
			pause.setIconResource(icon);
		}
		CharSequence pauseName = activity.getText(state.paused
				? R.string.passes_play
				: R.string.passes_pause);
		pause.setContentDescription(pauseName);
		ViewCompat.setTooltipText(pause, pauseName);
		step.setEnabled(runs && state.paused);
		// Where Step is: only while paused, where it changes.
		frame.setVisibility(runs && state.paused ? View.VISIBLE : View.GONE);
		frame.setText(activity.getString(R.string.passes_frame, state.frame));
		restart.setEnabled(runs);
		limit.setEnabled(runs);
		// Filled while the shader is throttled, outlined otherwise.
		limit.setChipBackgroundColor(ColorStateList.valueOf(state.limit > 0
				? MaterialColors.getColor(limit,
						com.google.android.material.R.attr.colorSecondaryContainer)
				: Color.TRANSPARENT));
		limit.setText(state.limit > 0
				? activity.getString(R.string.passes_limit, state.limit)
				: activity.getString(R.string.passes_no_limit));
		for (int i = 0; i < qualities.length; ++i) {
			qualities[i].setChecked(qualityValues[i] == state.quality);
		}
		if (animates && (state.shown == null) != (was.shown == null)) {
			// The chip fades through to the other one.
			TransitionManager.beginDelayedTransition(
					(ViewGroup) shownChip.getParent(),
					new Fade().setDuration(150));
		}
		qualityChip.setVisibility(state.available && state.shown == null
				? View.VISIBLE
				: View.GONE);
		shownChip.setVisibility(state.available && state.shown != null
				? View.VISIBLE
				: View.GONE);
		if (state.shown != null) {
			renderShownChip();
		}
		if (state.shown != was.shown || state.paused != was.paused) {
			ui.setPicture(state.shown != null, state.paused);
		}
		boolean selected = !Objects.equals(state.shown, was.shown);
		if (!state.passes.equals(was.passes) || (selected && collapsed)) {
			renderRows();
			renderFacts();
		} else if (selected) {
			// The selection moves and the shown row grows in place.
			if (animates && state.open) {
				TransitionManager.beginDelayedTransition(list,
						new AutoTransition().setDuration(200)
								.setInterpolator(new FastOutSlowInInterpolator()));
			}
			renderSelection();
			renderReads();
			renderFacts();
		} else if (state.facts != was.facts || state.paused != was.paused ||
				state.fit != was.fit) {
			renderFacts();
		}
		var rows = state.passes.rows();
		for (int i = 0; i < rows.size() && i < list.getChildCount(); ++i) {
			Bitmap picture = state.pictures.get(rows.get(i).file());
			ImageView image = list.getChildAt(i).findViewById(R.id.pass_picture);
			if (picture != null) {
				image.setImageBitmap(picture);
			} else {
				image.setImageResource(R.drawable.picture_empty_frame);
			}
		}
	}

	// The rows of what draws, and the rail beside them.
	private void renderRows() {
		var rows = state.passes.rows();
		boolean listed = rows.size() > 1;
		divider.setVisibility(listed ? View.VISIBLE : View.GONE);
		scroll.setVisibility(listed ? View.VISIBLE : View.GONE);
		list.removeAllViews();
		if (!listed) {
			list.setRail(null);
			key.setVisibility(View.GONE);
			return;
		}
		var first = new boolean[rows.size()];
		var reads = new ArrayList<PassRail.Read>();
		for (int r = 0; r < rows.size(); ++r) {
			var row = rows.get(r);
			first[r] = row.kind() == RunningPasses.Kind.FIRST_FRAME;
			for (var read : row.reads()) {
				reads.add(new PassRail.Read(read.row(), r,
						read.kind() == RunningPasses.ReadKind.LAST_FRAME));
			}
		}
		int selected = state.selected();
		var rail = PassRail.layout(first, reads, selected);
		float dp = activity.getResources().getDisplayMetrics().density;
		var inflater = LayoutInflater.from(activity);
		boolean badged = false;
		boolean dashed = false;
		for (int r = 0; r < rows.size(); ++r) {
			var row = rows.get(r);
			View view = inflater.inflate(R.layout.row_pass, list, false);
			var params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
			params.setMarginStart(Math.round((rail.pictureLeft() - 8) * dp));
			((TextView) view.findViewById(R.id.pass_name)).setText(
					row.file().getFileName());
			view.findViewById(R.id.pass_runs).setVisibility(row.output()
					? View.VISIBLE
					: View.INVISIBLE);
			if (first[r]) {
				View picture = view.findViewById(R.id.pass_picture);
				int size = Math.round(PassRail.FIRST_PICTURE * dp);
				picture.getLayoutParams().width = size;
				picture.getLayoutParams().height = size;
			}
			var file = row.file();
			var sizeLine = row.sizeLine();
			view.findViewById(R.id.pass_size).setOnClickListener(v ->
					openSizeLine(sizeLine));
			if (rail.collapsed() && r != selected && !rail.touches(r)) {
				view.setAlpha(.38f);
			}
			if (row.drawn()) {
				var tapped = row.output() ? null : file;
				view.setOnClickListener(v -> show(tapped));
			}
			badged |= rail.badge(r);
			list.addView(view);
		}
		for (var line : rail.lines()) {
			dashed |= line.form() == PassRail.Form.LANE && line.read().last();
		}
		list.setRail(rail);
		collapsed = rail.collapsed();
		listed = rail.listed();
		renderSelection();
		renderReads();
		// Said only for what is on screen: the badge alone is a pass that
		// reads its own last frame.
		key.setVisibility(badged || dashed ? View.VISIBLE : View.GONE);
		key.setText(dashed ? R.string.passes_key : R.string.passes_key_badge);
		key.setCompoundDrawablesRelativeWithIntrinsicBounds(
				list.key(badged, dashed), null, null, null);
		key.setPaddingRelative(Math.round(rail.pictureLeft() * dp), 0, 0, 0);
	}

	// What the selected row reads, as chips under it, where lines cannot
	// say it: the picture files the pass shown reads and starts from,
	// which are not rows; and when even its own lines need more than
	// four lanes, everything it starts from, reads, and is read by.
	private void renderReads() {
		var rows = state.passes.rows();
		int selected = state.selected();
		for (int r = 0; r < rows.size() && r < list.getChildCount(); ++r) {
			var row = rows.get(r);
			ViewGroup lines = list.getChildAt(r).findViewById(R.id.pass_reads);
			lines.removeAllViews();
			var starts = new ArrayList<Chip>();
			var reads = new ArrayList<Chip>();
			var readBy = new ArrayList<Chip>();
			boolean all = listed && r == selected;
			if (all || row.file().equals(state.shown)) {
				if (row.startsFrom() != null) {
					starts.add(chip(lines, row.startsFrom().getFileName(),
							R.drawable.ic_image));
				}
				for (var picture : row.pictures()) {
					reads.add(chip(lines, picture.getFileName(),
							R.drawable.ic_image));
				}
			}
			if (all) {
				for (var read : row.reads()) {
					String name = rows.get(read.row()).file().getFileName();
					if (read.row() == r) {
						reads.add(chip(lines, activity.getString(
								R.string.passes_itself_last), R.drawable.ic_history));
					} else if (read.kind() == RunningPasses.ReadKind.FIRST_FRAME) {
						starts.add(chip(lines, name, 0));
					} else if (read.kind() == RunningPasses.ReadKind.LAST_FRAME) {
						reads.add(chip(lines, activity.getString(
								R.string.passes_last_chip, name), R.drawable.ic_history));
					} else {
						reads.add(chip(lines, name, 0));
					}
				}
				for (int o = 0; o < rows.size(); ++o) {
					for (var read : rows.get(o).reads()) {
						if (o != r && read.row() == r) {
							readBy.add(chip(lines, rows.get(o).file()
									.getFileName(), 0));
							break;
						}
					}
				}
			}
			line(lines, R.string.passes_starts_from, starts);
			line(lines, R.string.passes_reads, reads);
			line(lines, R.string.passes_read_by, readBy);
			lines.setVisibility(lines.getChildCount() > 0
					? View.VISIBLE
					: View.GONE);
		}
	}

	// A line of chips after its label, wrapped when it is long.
	private void line(@NonNull ViewGroup lines, int label,
			@NonNull List<Chip> chips) {
		if (chips.isEmpty()) {
			return;
		}
		var inflater = LayoutInflater.from(activity);
		ChipGroup group = (ChipGroup) inflater.inflate(R.layout.pass_reads_line,
				lines, false);
		TextView text = (TextView) inflater.inflate(R.layout.pass_reads_label,
				group, false);
		text.setText(label);
		group.addView(text);
		for (var chip : chips) {
			group.addView(chip);
		}
		// Added after the row took its state, which reaches only the
		// views there were: these take it now.
		group.setActivated(lines.isActivated());
		lines.addView(group);
	}

	@NonNull
	private Chip chip(@NonNull ViewGroup parent, @NonNull String text,
			int icon) {
		Chip chip = (Chip) LayoutInflater.from(activity).inflate(
				R.layout.chip_pass_read, parent, false);
		chip.setText(text);
		if (icon != 0) {
			chip.setChipIconResource(icon);
			chip.setChipIconVisible(true);
		}
		return chip;
	}

	// The selected row is filled, its name in medium weight.
	private void renderSelection() {
		int selected = state.selected();
		for (int r = 0; r < list.getChildCount(); ++r) {
			View view = list.getChildAt(r);
			view.setActivated(r == selected);
			((TextView) view.findViewById(R.id.pass_name)).setTypeface(
					r == selected
							? Typeface.create("sans-serif-medium", Typeface.NORMAL)
							: Typeface.DEFAULT);
		}
	}

	// What is worth saying of each pass, from its facts: a second line
	// only when it says something; the pass shown grows by its facts
	// and its actions.
	private void renderFacts() {
		var rows = state.passes.rows();
		if (list.getChildCount() != rows.size()) {
			return;
		}
		int width = 0;
		int height = 0;
		for (var row : rows) {
			var facts = state.facts.get(row.file());
			if (row.output() && facts != null) {
				width = facts.width();
				height = facts.height();
			}
		}
		for (int r = 0; r < rows.size(); ++r) {
			var row = rows.get(r);
			View view = list.getChildAt(r);
			var facts = state.facts.get(row.file());
			var problem = facts != null
					? PassFactLine.problem(facts)
					: row.problems().isEmpty() ? null : row.problems().get(0);
			String second;
			if (problem != null) {
				second = problem.severity() == ProjectDiagnostic.Severity.ERROR
						? words.error(problem.line())
						: words.warning(problem.line());
			} else if (row.kind() == RunningPasses.Kind.FIRST_FRAME) {
				second = activity.getString(R.string.passes_first_frame);
			} else {
				second = facts != null
						? PassFactLine.second(facts, width, height, words)
						: null;
			}
			TextView line = view.findViewById(R.id.pass_line);
			line.setText(second);
			line.setVisibility(second != null ? View.VISIBLE : View.GONE);
			boolean error = problem != null &&
					problem.severity() == ProjectDiagnostic.Severity.ERROR;
			line.setCompoundDrawablesRelativeWithIntrinsicBounds(problem == null
					? 0
					: error ? R.drawable.ic_error_small : R.drawable.ic_warning_small,
					0, 0, 0);
			if (error) {
				line.setTextColor(MaterialColors.getColor(line,
						androidx.appcompat.R.attr.colorError));
			}
			boolean grown = row.file().equals(state.shown);
			String shown = grown && facts != null
					? PassFactLine.shown(facts, state.paused, words)
					: null;
			TextView factsLine = view.findViewById(R.id.pass_facts);
			factsLine.setText(shown);
			factsLine.setVisibility(shown != null ? View.VISIBLE : View.GONE);
			view.findViewById(R.id.pass_actions).setVisibility(grown
					? View.VISIBLE
					: View.GONE);
			Button open = view.findViewById(R.id.pass_open);
			int at = problem != null ? problem.line() : 0;
			open.setText(error && at > 0
					? activity.getString(R.string.passes_open_line, at)
					: activity.getString(R.string.passes_open));
			var file = row.file();
			open.setOnClickListener(v -> {
				close();
				files.open(file, error ? at : 0);
			});
			view.findViewById(R.id.pass_size).setVisibility(error
					? View.GONE
					: View.VISIBLE);
			boolean fitted = state.fit != null && state.fit.file().equals(file);
			Chip fit = view.findViewById(R.id.pass_fit);
			fit.setVisibility(!error && (fitted ||
					(facts != null && PassFactLine.outside(facts)))
					? View.VISIBLE
					: View.GONE);
			fit.setChecked(fitted);
			fit.setOnClickListener(v -> fit(fitted ? null : facts));
			view.setContentDescription(spoken(r, second, shown));
		}
	}

	// Go to the line that sets the size of a pass, or where one would
	// go when it has none. The completion of the directive lines offers
	// the scales 1, 0.5, 0.25 and 0.125 in that line's value.
	private void openSizeLine(@NonNull RunningPasses.SizeLine line) {
		close();
		files.open(line.file(), line.line());
	}

	// A row as it is read out: its file, what it says, what it reads,
	// what reads it, and whether it is shown; facts are read when the
	// row is, never as they change.
	@NonNull
	private String spoken(int r, @Nullable String second,
			@Nullable String shown) {
		var rows = state.passes.rows();
		var row = rows.get(r);
		var text = new StringBuilder(row.file().getFileName());
		for (String part : new String[]{second, shown}) {
			if (part != null) {
				text.append(", ").append(part);
			}
		}
		var reads = new ArrayList<String>();
		for (var read : row.reads()) {
			String name = rows.get(read.row()).file().getFileName();
			reads.add(read.row() == r
					? activity.getString(R.string.passes_a11y_itself)
					: switch (read.kind()) {
						case THIS_FRAME -> name;
						case LAST_FRAME -> activity.getString(
								R.string.passes_a11y_last, name);
						case FIRST_FRAME -> activity.getString(
								R.string.passes_a11y_first, name);
					});
		}
		if (!reads.isEmpty()) {
			text.append(", ").append(activity.getString(
					R.string.passes_a11y_reads, TextUtils.join(", ", reads)));
		}
		var readers = new ArrayList<String>();
		for (int o = 0; o < rows.size(); ++o) {
			for (var read : rows.get(o).reads()) {
				String name = rows.get(o).file().getFileName();
				if (o != r && read.row() == r && !readers.contains(name)) {
					readers.add(name);
				}
			}
		}
		if (!readers.isEmpty()) {
			text.append(", ").append(activity.getString(
					R.string.passes_a11y_read_by, TextUtils.join(", ", readers)));
		}
		if (row.file().equals(state.shown)) {
			text.append(", ").append(activity.getString(
					R.string.passes_a11y_shown));
		}
		return text.toString();
	}

	// The facts as they are now: the engine may fill its own again.
	@NonNull
	private static PassFacts copy(@NonNull PassFacts f) {
		return new PassFacts(f.file(), f.drawn(), f.problems(), f.width(),
				f.height(), f.format(), f.sample(), clone(f.same()),
				clone(f.low()), clone(f.high()), f.nonFinite(), f.unchanged());
	}

	@Nullable
	private static float[] clone(@Nullable float[] values) {
		return values != null ? values.clone() : null;
	}

	@NonNull
	private static PassFactLine.Words words(@NonNull Context context) {
		return new PassFactLine.Words() {
			@Override
			public String error(int line) {
				return line > 0
						? context.getString(R.string.passes_error_line, line)
						: context.getString(R.string.passes_error);
			}

			@Override
			public String warning(int line) {
				return line > 0
						? context.getString(R.string.passes_warning_line, line)
						: context.getString(R.string.passes_warning);
			}

			@Override
			public String everyPixel(String values) {
				return context.getString(R.string.passes_every_pixel, values);
			}

			@Override
			public String values(String low, String high) {
				return context.getString(R.string.passes_values, low, high);
			}

			@Override
			public String nonFinite() {
				return context.getString(R.string.passes_non_finite);
			}

			@Override
			public String size(int width, int height) {
				return context.getString(R.string.passes_size, width, height);
			}

			@Override
			public String unchanged(long seconds) {
				return context.getString(R.string.passes_unchanged, seconds);
			}
		};
	}

	// The name of the pass shown, cut in the middle at 120 dp; a pause
	// mark before it while paused.
	private void renderShownChip() {
		String name = state.shown.getFileName();
		var paint = shownChip.getPaint();
		float most = nameRoom;
		String cut = NameCut.cut(name, t -> paint.measureText(t) <= most);
		shownChip.setText(state.fit != null
				? activity.getString(R.string.passes_name_fit, cut)
				: cut);
		shownChip.setContentDescription(activity.getString(
				R.string.passes_showing, name));
		shownChip.setChipIconResource(R.drawable.ic_pause);
		shownChip.setChipIconVisible(state.paused);
	}

	// Where the toolbar is crowded, things give way in this order: the
	// second line at its end, but never into the frame rate before it;
	// then the pass's name in the chip, in its middle, down to three
	// characters and its ending; only then the title, at its end.
	private void fitShownChip() {
		if (state.shown == null || shownChip.getVisibility() != View.VISIBLE ||
				shownChip.getWidth() == 0) {
			return;
		}
		float dp = activity.getResources().getDisplayMetrics().density;
		var paint = shownChip.getPaint();
		String name = state.shown.getFileName();
		// Everything of the chip but the name: padding, marks, ✕.
		float chrome = shownChip.getWidth() - paint.measureText(
				NameCut.cut(name, t -> paint.measureText(t) <= nameRoom));
		String whole = title.getName();
		float titleNeeds = whole == null ? 0 : title.getPaint()
				.measureText(whole) + title.getCompoundPaddingLeft() +
				title.getCompoundPaddingRight() + 1;
		CharSequence line = subtitle.getText();
		int dot = line == null ? -1 : line.toString().indexOf(" \u00b7 ");
		float rateNeeds = dot < 0 ? 0 : subtitle.getPaint().measureText(
				line.toString().substring(0, dot + 2)) + 1;
		View column = (View) title.getParent();
		float room = shownChip.getRight() - column.getLeft() -
				Math.max(titleNeeds, rateNeeds) - chrome - 4 * dp;
		int ending = name.lastIndexOf('.');
		String least = ending > 3
				? name.substring(0, 3) + NameCut.ELLIPSIS + name.substring(ending)
				: name;
		room = Math.max(paint.measureText(least),
				Math.min(120 * dp, room));
		if (Math.abs(room - nameRoom) > 1) {
			nameRoom = room;
			shownChip.post(() -> {
				if (state.shown != null) {
					renderShownChip();
				}
			});
		}
	}

	// The picture as the screen shows it, which shows no alpha; a copy,
	// as the engine fills its pixels again for the next round.
	@NonNull
	private static Bitmap opaque(@NonNull PassPicture picture) {
		int[] argb = Arrays.copyOf(picture.argb(),
				picture.width() * picture.height());
		for (int i = 0; i < argb.length; ++i) {
			argb[i] |= 0xff000000;
		}
		return Bitmap.createBitmap(argb, picture.width(), picture.height(),
				Bitmap.Config.ARGB_8888);
	}

	private void spreadQualities(@NonNull ChipGroup row) {
		float dp = activity.getResources().getDisplayMetrics().density;
		int space = row.getWidth() - row.getPaddingLeft() -
				row.getPaddingRight();
		if (space <= 0) {
			return;
		}
		// Each chip's own width: 48 dp, or its label with 4 dp a side.
		int natural = 0;
		for (var chip : qualities) {
			natural += Math.max(Math.round(48 * dp), Math.round(
					chip.getPaint().measureText(chip.getText().toString()) +
							8 * dp));
		}
		int least = Math.round(4 * dp);
		int gaps = qualities.length - 1;
		int gap;
		int width;
		if (natural + gaps * least <= space) {
			gap = (space - natural) / gaps;
			width = ViewGroup.LayoutParams.WRAP_CONTENT;
		} else {
			int perLine = (qualities.length + 1) / 2;
			gap = Math.round(8 * dp);
			width = (space - (perLine - 1) * gap) / perLine;
		}
		boolean changed = Math.abs(gap - row.getChipSpacingHorizontal()) > 1;
		for (var chip : qualities) {
			changed |= chip.getLayoutParams().width != width;
		}
		if (!changed) {
			return;
		}
		row.post(() -> {
			row.setChipSpacingHorizontal(gap);
			for (var chip : qualities) {
				// The label in the middle of the width it has.
				float label = chip.getPaint().measureText(
						chip.getText().toString());
				float own = width > 0
						? width
						: Math.max(48 * dp, label + 8 * dp);
				float pad = Math.max(0, (own - label) / 2);
				chip.setChipStartPadding(pad);
				chip.setChipEndPadding(pad);
				chip.getLayoutParams().width = width;
				chip.requestLayout();
			}
		});
	}

	// Whether the system plays animations.
	private static boolean animates(@NonNull Context context) {
		return Settings.Global.getFloat(context.getContentResolver(),
				Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
	}
}
