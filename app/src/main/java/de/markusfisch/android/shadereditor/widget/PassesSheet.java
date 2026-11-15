package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Consumer;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.Fit;
import de.markusfisch.android.shadereditor.opengl.PassPicture;
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
	 * @param quality  the quality chosen
	 * @param pictures the latest small picture of each pass that draws
	 */
	record State(boolean open, boolean paused, @NonNull RunningPasses passes,
			float quality, @NonNull Map<ProjectPath, Bitmap> pictures) {
		static final State CLOSED = new State(false, false,
				RunningPasses.NONE, 1f, Map.of());

		boolean runs() {
			return !passes.rows().isEmpty();
		}

		State withOpen(boolean open) {
			return new State(open, paused, passes, quality, pictures);
		}

		State withPaused(boolean paused) {
			return new State(open, paused, passes, quality, pictures);
		}

		State withPasses(@NonNull RunningPasses passes) {
			return new State(open, paused, passes, quality, pictures);
		}

		State withQuality(float quality) {
			return new State(open, paused, passes, quality, pictures);
		}

		State withPictures(@NonNull Map<ProjectPath, Bitmap> pictures) {
			return new State(open, paused, passes, quality, pictures);
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
	private final View divider;
	private final View scroll;
	private final PassList list;
	private final TextView key;
	@NonNull
	private State state = State.CLOSED;
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
		divider = sheet.findViewById(R.id.passes_divider);
		scroll = sheet.findViewById(R.id.passes_scroll);
		list = sheet.findViewById(R.id.passes_list);
		key = sheet.findViewById(R.id.passes_key);
		view.setChipOpener(this::toggle);
		render(state);
		renderRows();
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
		view.watchPasses(null);
		// What it showed stays while it slides away.
		set(state.withOpen(false));
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
				set(state.withPasses(passes).withQuality(view.getQuality()));
			}
		}));
		view.watchPasses(new ShaderEngine.PassWatcher() {
			@Override
			public void onPictures(@NonNull List<PassPicture> pictures) {
				// Copied before this returns, off the main thread: the
				// next round fills the pictures again.
				var bitmaps = new HashMap<ProjectPath, Bitmap>();
				for (var picture : pictures) {
					bitmaps.put(picture.file(), opaque(picture));
				}
				handler.post(() -> {
					if (listening == token) {
						set(state.withPictures(bitmaps));
					}
				});
			}
		});
		behavior.setMaxHeight(Math.round(
				((View) sheet.getParent()).getHeight() * .7f));
		set(state.withOpen(true).withPasses(view.passes())
				.withQuality(view.getQuality()));
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
		if (!state.passes.equals(was.passes)) {
			renderRows();
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
		int output = rows.size() - 1;
		for (int r = 0; r < rows.size(); ++r) {
			var row = rows.get(r);
			first[r] = row.kind() == RunningPasses.Kind.FIRST_FRAME;
			if (row.output()) {
				output = r;
			}
			for (var read : row.reads()) {
				reads.add(new PassRail.Read(read.row(), r,
						read.kind() == RunningPasses.ReadKind.LAST_FRAME));
			}
		}
		var rail = PassRail.layout(first, reads, output);
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
				TextView line = view.findViewById(R.id.pass_line);
				line.setText(R.string.passes_first_frame);
				line.setVisibility(View.VISIBLE);
			}
			if (rail.collapsed() && r != output && !rail.touches(r)) {
				view.setAlpha(.38f);
			}
			badged |= rail.badge(r);
			list.addView(view);
		}
		for (var line : rail.lines()) {
			dashed |= line.form() == PassRail.Form.LANE && line.read().last();
		}
		list.setRail(rail);
		// Said only for what is on screen: the badge alone is a pass that
		// reads its own last frame.
		key.setVisibility(badged || dashed ? View.VISIBLE : View.GONE);
		key.setText(dashed ? R.string.passes_key : R.string.passes_key_badge);
		key.setCompoundDrawablesRelativeWithIntrinsicBounds(
				list.key(badged, dashed), null, null, null);
		key.setPaddingRelative(Math.round(rail.pictureLeft() * dp), 0, 0, 0);
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

	// Whether the system plays animations.
	private static boolean animates(@NonNull Context context) {
		return Settings.Global.getFloat(context.getContentResolver(),
				Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
	}
}
