package de.markusfisch.android.shadereditor.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.AddUniformActivity;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.legacy.LegacySamplerSettings;
import de.markusfisch.android.shadereditor.widget.BackBufferParametersView;
import de.markusfisch.android.shadereditor.widget.TextureParametersView;

public class TextureParametersFragment extends Fragment {
	private static final String TYPE = "type";
	private static final String NAME = "name";

	private TextureParametersView textureParameterView;
	private BackBufferParametersView backBufferParametersView;
	private String samplerType;
	private String textureName;
	private LegacySamplerSettings defaults;
	private boolean isBackBuffer;

	public static Fragment newInstance(String type, String name) {
		Bundle args = new Bundle();
		args.putString(TYPE, type);
		args.putString(NAME, name);

		TextureParametersFragment fragment =
				new TextureParametersFragment();
		fragment.setArguments(args);

		return fragment;
	}

	@Override
	public View onCreateView(
			@NonNull LayoutInflater inflater,
			ViewGroup container,
			Bundle state) {
		Activity activity = getActivity();
		if (activity == null) {
			return null;
		}
		activity.setTitle(R.string.texture_parameters);

		Bundle args = getArguments();
		if (args == null ||
				(samplerType = args.getString(TYPE)) == null ||
				(textureName = args.getString(NAME)) == null) {
			throw new IllegalArgumentException(
					"Missing type and name arguments");
		}

		isBackBuffer = UniformTable.BACKBUFFER.equals(textureName);

		int layout;
		if (isBackBuffer) {
			defaults = LegacySamplerSettings.BACKBUFFER;
			layout = R.layout.fragment_backbuffer_parameters;
		} else {
			defaults = LegacySamplerSettings.TEXTURE;
			layout = R.layout.fragment_texture_parameters;
		}

		View view = inflater.inflate(layout, container, false);

		textureParameterView = view.findViewById(R.id.texture_parameters);
		textureParameterView.setDefaults(defaults);

		if (isBackBuffer) {
			backBufferParametersView = view.findViewById(
					R.id.backbuffer_parameters);
		}

		view.findViewById(R.id.insert_code).setOnClickListener(v -> insertUniform());

		return view;
	}

	private void insertUniform() {
		Activity activity = getActivity();
		if (activity == null) {
			return;
		}

		LegacySamplerSettings settings =
				textureParameterView.getParameters(defaults);
		if (isBackBuffer) {
			settings = settings.withPreset(backBufferParametersView.getPreset());
		}

		AddUniformActivity.setAddUniformResult(activity,
				"uniform " + samplerType + " " + textureName + ";" +
						settings.comment(defaults));

		activity.finish();
	}
}