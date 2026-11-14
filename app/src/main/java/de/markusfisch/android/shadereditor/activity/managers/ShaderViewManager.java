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
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.widget.ShaderView;

public class ShaderViewManager {
	public interface Listener {
		void onFramesPerSecond(int fps);

		void onInfoLog(@NonNull List<ProjectDiagnostic> infoLog);

		void onQualityChanged(float quality);
	}

	@NonNull
	private final ShaderView shaderView;
	@NonNull
	private final Spinner qualitySpinner;
	@NonNull
	private final Listener listener;
	@NonNull
	private float[] qualityValues;
	private float quality = 1f;
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

	public void onPause() {
		if (shaderView.getVisibility() == View.VISIBLE) {
			shaderView.onPause();
		}
	}

	public void onResume() {
		if (shaderView.getVisibility() == View.VISIBLE) {
			shaderView.onResume();
		}
	}

	public void setShader(@NonNull String text, float quality) {
		setQuality(quality);
		shaderView.setShader(text, quality);
	}

	/** Run a prepared project at quality. */
	public void setPlan(@NonNull Preparation preparation, float quality) {
		setQuality(quality);
		shaderView.setPlan(preparation, quality);
	}

	public void setVisibility(boolean visible) {
		shaderView.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	// The brightest the picture may be, of 255 (PictureLimit).
	public void limitPicture(int ceiling, boolean fade) {
		shaderView.getEngine().limitPicture(ceiling, fade);
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

	public void setQuality(float quality) {
		for (int i = 0; i < qualityValues.length; ++i) {
			if (qualityValues[i] == quality) {
				qualitySpinner.setSelection(i);
				this.quality = quality;
				return;
			}
		}
	}

	private void initShaderView() {
		shaderView.getEngine().setListener(new ShaderEngine.Listener() {
			@Override
			public void onFramesPerSecond(int fps) {
				listener.onFramesPerSecond(fps);
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
