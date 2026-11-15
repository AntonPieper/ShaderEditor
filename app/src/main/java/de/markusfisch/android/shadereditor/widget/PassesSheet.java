package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Space;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.Fit;
import de.markusfisch.android.shadereditor.opengl.RunningPasses;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
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
	}

	/** The code over the picture. */
	public interface Code {
		/** Set the code aside while the sheet is open, or give it back. */
		void setAside(boolean aside, boolean animate);
	}

	/**
	 * What the sheet shows.
	 *
	 * @param open    whether it is open
	 * @param paused  whether the picture is paused
	 * @param passes  what draws now
	 * @param quality the quality chosen
	 */
	record State(boolean open, boolean paused, @NonNull RunningPasses passes,
			float quality) {
		boolean runs() {
			return !passes.rows().isEmpty();
		}
	}

	private final AppCompatActivity activity;
	private final Engine view;
	private final Code editor;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final View sheet;
	private final BottomSheetBehavior<View> behavior;
	private final OnBackPressedCallback back;
	private final MaterialButton pause;
	private final View step;
	private final View restart;
	private final Chip[] qualities;
	private final float[] qualityValues;
	@NonNull
	private State state = new State(false, false, RunningPasses.NONE, 1f);
	// What hears the engine while the sheet is open, so that what it
	// heard before it was closed is dropped.
	private Object listening;

	public PassesSheet(@NonNull AppCompatActivity activity,
			@NonNull Engine view, @NonNull Code editor) {
		this.activity = activity;
		this.view = view;
		this.editor = editor;
		ViewGroup coordinator = activity.findViewById(R.id.main_coordinator);
		sheet = LayoutInflater.from(activity).inflate(R.layout.sheet_passes,
				coordinator, false);
		coordinator.addView(sheet);
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
		step.setOnClickListener(v -> view.step());
		restart = sheet.findViewById(R.id.passes_restart);
		restart.setOnClickListener(v -> view.restart());
		ViewCompat.setTooltipText(step, step.getContentDescription());
		ViewCompat.setTooltipText(restart, restart.getContentDescription());

		var names = activity.getResources().getStringArray(R.array.quality_names);
		var values = activity.getResources().getStringArray(R.array.quality_values);
		qualities = new Chip[names.length];
		qualityValues = new float[values.length];
		LinearLayout row = sheet.findViewById(R.id.passes_quality);
		var inflater = LayoutInflater.from(activity);
		for (int i = 0; i < names.length; ++i) {
			float quality = Float.parseFloat(values[i]);
			qualityValues[i] = quality;
			if (i > 0) {
				row.addView(new Space(activity), new LinearLayout.LayoutParams(
						0, 1, 1f));
			}
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
		view.setChipOpener(this::toggle);
		render(state);
	}

	/** Open the sheet, or close it when it is open. */
	public void toggle() {
		if (state.open) {
			close();
		} else {
			open();
		}
	}

	/** Close the sheet; nothing of it is heard any more. */
	public void close() {
		if (!state.open) {
			return;
		}
		listening = null;
		view.setPassesListener(null);
		set(new State(false, state.paused, RunningPasses.NONE, state.quality));
	}

	/** The screen stops: the sheet closes. */
	public void onStop() {
		close();
	}

	public void destroy() {
		close();
		handler.removeCallbacksAndMessages(null);
		view.setChipOpener(null);
	}

	private void open() {
		Object token = new Object();
		listening = token;
		// The listener may be called on any thread, also once more
		// after it was replaced.
		view.setPassesListener(passes -> handler.post(() -> {
			if (listening == token) {
				set(new State(true, state.paused, passes, view.getQuality()));
			}
		}));
		behavior.setMaxHeight(Math.round(
				((View) sheet.getParent()).getHeight() * .7f));
		set(new State(true, state.paused, view.passes(), view.getQuality()));
	}

	private void setPaused(boolean paused) {
		view.setPaused(paused);
		set(new State(state.open, paused, state.passes, state.quality));
	}

	private void chooseQuality(float quality) {
		view.chooseQuality(quality);
		set(new State(state.open, state.paused, state.passes, quality));
	}

	private void set(@NonNull State next) {
		State was = state;
		state = next;
		render(was);
	}

	// The one place that shows the state.
	private void render(@NonNull State was) {
		if (state.open != was.open) {
			back.setEnabled(state.open);
			editor.setAside(state.open, animates(activity));
			behavior.setState(state.open
					? BottomSheetBehavior.STATE_EXPANDED
					: BottomSheetBehavior.STATE_HIDDEN);
		}
		boolean runs = state.runs();
		pause.setEnabled(runs);
		pause.setIconResource(state.paused
				? R.drawable.ic_play_arrow
				: R.drawable.ic_pause);
		CharSequence pauseName = activity.getText(state.paused
				? R.string.passes_play
				: R.string.passes_pause);
		pause.setContentDescription(pauseName);
		ViewCompat.setTooltipText(pause, pauseName);
		step.setEnabled(runs && state.paused);
		restart.setEnabled(runs);
		for (int i = 0; i < qualities.length; ++i) {
			qualities[i].setChecked(qualityValues[i] == state.quality);
		}
	}

	// Whether the system plays animations.
	private static boolean animates(@NonNull Context context) {
		return Settings.Global.getFloat(context.getContentResolver(),
				Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
	}
}
