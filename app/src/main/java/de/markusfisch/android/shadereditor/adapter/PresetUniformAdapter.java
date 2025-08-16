package de.markusfisch.android.shadereditor.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Locale;

import de.markusfisch.android.shadereditor.R;

public class PresetUniformAdapter extends ArrayAdapter<PresetUniformAdapter.DisplayUniform> {
	private final String uniformFormat;

	public PresetUniformAdapter(@NonNull Context context, @NonNull List<DisplayUniform> uniforms) {
		super(context, R.layout.row_preset, uniforms);
		this.uniformFormat = context.getString(R.string.uniform_format);
	}

	@NonNull
	@Override
	public View getView(int position, View convertView, @NonNull ViewGroup parent) {
		if (convertView == null) {
			convertView = LayoutInflater
					.from(parent.getContext())
					.inflate(R.layout.row_preset, parent, false);
		}

		ViewHolder holder = getViewHolder(convertView);
		DisplayUniform uniform = getItem(position);

		if (uniform != null) {
			holder.name.setText(String.format(
					Locale.US,
					uniformFormat,
					uniform.name(),
					uniform.type()));
			holder.rationale.setText(uniform.description());
		}

		return convertView;
	}

	@NonNull
	private ViewHolder getViewHolder(@NonNull View view) {
		ViewHolder holder = (ViewHolder) view.getTag();
		if (holder == null) {
			holder = new ViewHolder();
			holder.name = view.findViewById(R.id.name);
			holder.rationale = view.findViewById(R.id.rationale);
			view.setTag(holder);
		}
		return holder;
	}

	/**
	 * A simple record to hold the final, combined data for display in the UI.
	 */
	public record DisplayUniform(
			@NonNull String name,
			@NonNull String type,
			@NonNull String description,
			@NonNull String statement
	) {
		// Override toString for the filter to work on the name.
		@NonNull
		@Override
		public String toString() {
			return name;
		}
	}

	private static final class ViewHolder {
		private TextView name;
		private TextView rationale;
	}
}