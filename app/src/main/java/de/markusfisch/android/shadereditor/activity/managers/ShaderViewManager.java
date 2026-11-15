package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.Fit;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.widget.ShaderView;

public class ShaderViewManager {
	public interface Listener {
		void onFramesPerSecond(int fps);

		void onInfoLog(@NonNull List<ProjectDiagnostic> infoLog);

		// Nothing runs, so nothing reports: what was reported is gone,
		// and what comes next is not known.
		void onNothingReported();

		// Before onInfoLog: whether the picture is the last that worked
		// while infoLog is of a newer build that did not.
		void onLastPicture(boolean shown);

		void onQualityChanged(float quality);
	}

	@NonNull
	private final ShaderView shaderView;
	@NonNull
	private final Spinner qualitySpinner;
	@NonNull
	private final Listener listener;
	// Told whether a picture is being drawn, from the engine's frame
	// rate; on any thread.
	@Nullable
	private volatile DrawingListener drawing;
	@NonNull
	private float[] qualityValues;
	private float quality = 1f;
	// Whether the run mode shows the picture behind the code, and
	// whether there is a picture to show.
	private boolean visible = true;
	private boolean picture = true;
	private ArrayAdapter<CharSequence> adapter;
	@Nullable
	private CharSequence frameRate;

	public ShaderViewManager(@NonNull Activity activity,
			@NonNull ShaderView shaderView,
			@NonNull Spinner qualitySpinner,
			@NonNull Listener listener) {
		this.shaderView = shaderView;
		this.qualitySpinner = qualitySpinner;
		this.listener = listener;

		initQualitySpinner(activity);
		initShaderView();
	}

	// The GL thread follows the screen also while there is no picture,
	// so that one shown later draws.
	public void onPause() {
		if (visible) {
			shaderView.onPause();
		}
	}

	public void onResume() {
		if (visible) {
			shaderView.onResume();
		}
	}

	public void setShader(@NonNull String text, float quality) {
		setQuality(quality);
		shaderView.setShader(text, quality);
		showPicture(true);
	}

	/**
	 * Run a prepared project at quality, a revision of picture, which is
	 * equal for the edits of what runs: if it fails, what is shown stays
	 * only when it is of the same picture, and goes otherwise.
	 */
	public void setPlan(@NonNull Preparation preparation, float quality,
			@NonNull Object picture) {
		setQuality(quality);
		shaderView.setPlan(preparation, quality, picture);
		showPicture(true);
	}

	/**
	 * Run a prepared project of a tuning session, a revision of the
	 * picture shown, which goes on from it: time and frame go on, and
	 * the previous frames of its passes when no pass changed its size
	 * or format. tuning, if not null, is the uniform that replaced a
	 * tuned number of the text, and hears whether the build draws. The
	 * quality stays as it is.
	 */
	public void continuePlan(@NonNull Preparation preparation, float quality,
			@NonNull Object picture, @Nullable Tuning tuning) {
		shaderView.continuePlan(preparation, quality, picture, tuning);
	}

	/**
	 * Map the pass of fit from its range to 0..1, the same for red,
	 * green and blue, while it is on the screen, and its small picture;
	 * null for none. A range across 0 puts 0 at grey.
	 */
	public void setFit(@Nullable Fit fit) {
		shaderView.setFit(fit);
	}

	/**
	 * Let watcher hear small pictures of the passes about four times a
	 * second, on the GL thread, or no one for null; nothing of it costs
	 * anything while no one watches.
	 */
	public void watchPasses(@Nullable ShaderEngine.PassWatcher watcher) {
		shaderView.watchPasses(watcher);
	}

	/**
	 * Draw at most limit frames a second, as fast as the screen for 0,
	 * as the shader's settings keep it. Main thread.
	 */
	public void setFrameRateLimit(int limit) {
		shaderView.setFrameRateLimit(limit);
	}

	/**
	 * Pause the picture at the frame drawn last, or let it run on from
	 * there. Paused, nothing is drawn but what is asked for.
	 */
	public void setPaused(boolean paused) {
		shaderView.setPaused(paused);
	}

	/** While paused, draw the next frame; every pass advances together. */
	public void step() {
		shaderView.step();
	}

	/** Draw the first frames again, from time and frame 0, without a build. */
	public void restart() {
		shaderView.restart();
	}

	/**
	 * Show the pass of file full size instead of the output, or the
	 * output again for null. Nothing that runs changes; the library's
	 * picture stays of the output.
	 */
	public void showPass(@Nullable ProjectPath file) {
		shaderView.showPass(file);
	}

	/** Show the value tuning has now with the next frame. */
	public void applyTuning() {
		shaderView.applyTuning();
	}

	/**
	 * Show no picture until the next shader or plan is set, as when
	 * what is open runs nothing; the engine cannot clear its picture.
	 */
	/**
	 * Show no picture: nothing runs. What a build reported before is
	 * not of what is shown now, so the listener hears that no picture
	 * is the last that worked and that nothing reports; a report of why
	 * nothing runs may follow.
	 */
	public void showNothing() {
		showPicture(false);
		listener.onLastPicture(false);
		listener.onNothingReported();
	}

	public void setVisibility(boolean visible) {
		this.visible = visible;
		apply();
	}

	private void showPicture(boolean picture) {
		if (this.picture != picture) {
			this.picture = picture;
			apply();
		}
	}

	private void apply() {
		shaderView.setVisibility(visible && picture ? View.VISIBLE : View.GONE);
	}

	// The brightest the picture may be, of 255 (PictureLimit).
	public void limitPicture(int ceiling, boolean fade) {
		shaderView.getEngine().limitPicture(ceiling, fade);
	}

	/** Whether the picture's context draws into rgba16f targets. */
	public boolean drawsHalfFloat() {
		return shaderView.getEngine().drawsHalfFloat();
	}

	public void requestThumbnail(@NonNull ShaderEngine.ThumbnailCallback callback) {
		shaderView.getEngine().requestThumbnail(callback);
	}

	/** Show the frame rate beside the quality, none when null. */
	public void setFrameRate(@Nullable CharSequence frameRate) {
		if (!TextUtils.equals(frameRate, this.frameRate)) {
			this.frameRate = frameRate;
			adapter.notifyDataSetChanged();
		}
	}

	/**
	 * Return the width of the quality chip with frameRate in it, or -1
	 * before it was laid out. It is measured with the widest quality, so
	 * that choosing another quality does not move the frame rate.
	 */
	public int chipWidth(@NonNull CharSequence frameRate) {
		if (!(qualitySpinner.getSelectedView() instanceof TextView chip)) {
			return -1;
		}
		float widest = 0;
		for (int i = adapter.getCount(); i-- > 0; ) {
			widest = Math.max(widest, chip.getPaint().measureText(
					chip.getContext().getString(R.string.frame_rate_quality,
							frameRate, adapter.getItem(i))));
		}
		return Math.round(widest) + chip.getTotalPaddingLeft() +
				chip.getTotalPaddingRight();
	}

	public void setQuality(float quality) {
		for (int i = 0; i < qualityValues.length; ++i) {
			if (qualityValues[i] == quality) {
				qualitySpinner.setSelection(i);
				this.quality = quality;
				return;
			}
		}
	}

	/** Hears whether a picture is being drawn. */
	public interface DrawingListener {
		/** On any thread. */
		void onDrawing(boolean drawing);
	}

	/**
	 * Tell drawing whether a picture is being drawn, as the engine
	 * reports its frame rate: frames come, or none does.
	 */
	public void setDrawingListener(@Nullable DrawingListener drawing) {
		this.drawing = drawing;
	}

	private void initShaderView() {
		shaderView.getEngine().setListener(new ShaderEngine.Listener() {
			@Override
			public void onFramesPerSecond(int fps) {
				listener.onFramesPerSecond(fps);
				DrawingListener told = drawing;
				if (told != null) {
					told.onDrawing(fps > 0);
				}
			}

			@Override
			public void onLastPicture(boolean shown) {
				listener.onLastPicture(shown);
			}

			@Override
			public void onDiagnostics(@NonNull List<ProjectDiagnostic> infoLog) {
				listener.onInfoLog(infoLog);
			}
		});
	}

	private void initQualitySpinner(@NonNull Activity activity) {
		String[] qualityStringValues =
				activity.getResources().getStringArray(R.array.quality_values);
		qualityValues = new float[qualityStringValues.length];
		for (int i = 0; i < qualityStringValues.length; ++i) {
			qualityValues[i] = Float.parseFloat(qualityStringValues[i]);
		}

		// The frame rate stands before the quality chosen, when it is
		// not in the toolbar's second line.
		adapter = new ArrayAdapter<>(activity,
				R.layout.toolbar_quality,
				activity.getResources().getTextArray(R.array.quality_names)) {
			@NonNull
			@Override
			public View getView(int position, @Nullable View convertView,
					@NonNull ViewGroup parent) {
				TextView view = (TextView) super.getView(position,
						convertView, parent);
				if (frameRate != null) {
					view.setText(activity.getString(R.string.frame_rate_quality,
							frameRate, getItem(position)));
				}
				return view;
			}
		};
		adapter.setDropDownViewResource(
				android.R.layout.simple_spinner_dropdown_item);
		qualitySpinner.setAdapter(adapter);
		qualitySpinner.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view,
					int position, long id) {
				float q = qualityValues[position];
				if (q == quality) {
					return;
				}
				quality = q;
				listener.onQualityChanged(quality);
				shaderView.setQuality(quality);
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
	}
}
