package de.markusfisch.android.shadereditor.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.stream.Collectors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.AddUniformActivity;
import de.markusfisch.android.shadereditor.adapter.PresetUniformAdapter;
import de.markusfisch.android.shadereditor.platform.data.PlatformBindingCatalog;

public class UniformPresetPageFragment extends AddUniformPageFragment {
	private PresetUniformAdapter uniformsAdapter;

	@Override
	public View onCreateView(
			@NonNull LayoutInflater inflater,
			ViewGroup container,
			Bundle state) {
		View view = inflater.inflate(
				R.layout.fragment_uniform_preset_page,
				container,
				false);

		ListView listView = view.findViewById(R.id.uniforms);
		initListView(listView);

		return view;
	}

	@Override
	protected void onSearch(@Nullable String query) {
		if (uniformsAdapter != null) {
			uniformsAdapter.getFilter().filter(query);
		}
	}

	private void initListView(@NonNull ListView listView) {
		// The editor fragment reads directly from the same platform catalog,
		// guaranteeing it's in sync with the engine's default configuration.
		var declarations = PlatformBindingCatalog.getMetadata();

		var displayUniforms = declarations.stream()
				.map(decl -> {
					String statement = String.format(
							"uniform %s %s;",
							decl.glslType(),
							decl.uniformName()
					);
					return new PresetUniformAdapter.DisplayUniform(
							decl.uniformName(),
							decl.glslType(),
							getString(decl.descriptionResId()),
							statement
					);
				})
				.collect(Collectors.toList());

		uniformsAdapter = new PresetUniformAdapter(requireContext(), displayUniforms);
		listView.setAdapter(uniformsAdapter);
		listView.setOnItemClickListener((parent, view, position, id) ->
				addUniform(uniformsAdapter.getItem(position)));
	}

	private void addUniform(@Nullable PresetUniformAdapter.DisplayUniform uniform) {
		if (uniform == null) return;

		Activity activity = getActivity();
		if (activity != null) {
			AddUniformActivity.setAddUniformResult(activity, uniform.statement());
			activity.finish();
		}
	}
}