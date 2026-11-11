package de.markusfisch.android.shadereditor.adapter;

import android.content.Context;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.ShaderRenderer;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;

public class PresetUniformAdapter extends BaseAdapter implements Filterable {
	public static final class Uniform {
		public final String type;
		public final String name;

		private final String rationale;
		private final int minSdk;

		private boolean isAvailable() {
			return minSdk <= Build.VERSION.SDK_INT;
		}

		public boolean isSampler() {
			return type.startsWith("sampler");
		}

		private Uniform(String type, String name, String rationale) {
			this(type, name, rationale, 0);
		}

		private Uniform(
				String type,
				String name,
				String rationale,
				int minSdk) {
			this.type = type;
			this.name = name;
			this.rationale = rationale;
			this.minSdk = minSdk;
		}
	}

	private final String uniformFormat;
	private final List<Uniform> uniforms;
	private final UniformFilter filter = new UniformFilter();

	private List<Uniform> filteredUniforms;

	public PresetUniformAdapter(Context context) {
		uniformFormat = context.getString(R.string.uniform_format);
		uniforms = Arrays.asList(
				new Uniform(
						"sampler2D",
						ShaderRenderer.UNIFORM_BACKBUFFER,
						context.getString(R.string.previous_frame)),
				builtin(context, "battery"),
				new Uniform(
						"vec2",
						ShaderRenderer.UNIFORM_CAMERA_ADDENT,
						context.getString(R.string.camera_addent),
						Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1),
				new Uniform(
						"samplerExternalOES",
						ShaderRenderer.UNIFORM_CAMERA_BACK,
						context.getString(R.string.camera_back),
						Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1),
				new Uniform(
						"samplerExternalOES",
						ShaderRenderer.UNIFORM_CAMERA_FRONT,
						context.getString(R.string.camera_front),
						Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1),
				new Uniform(
						"mat2",
						ShaderRenderer.UNIFORM_CAMERA_ORIENTATION,
						context.getString(R.string.camera_orientation),
						Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1),
				builtin(context, "date"),
				builtin(context, "daytime"),
				builtin(context, "frame"),
				builtin(context, "ftime"),
				builtin(context, "gravity"),
				builtin(context, "gyroscope"),
				builtin(context, "lastNotificationTime"),
				builtin(context, "inclination"),
				builtin(context, "inclinationMatrix"),
				builtin(context, "light"),
				builtin(context, "linear"),
				builtin(context, "magnetic"),
				builtin(context, "nightMode"),
				builtin(context, "notificationCount"),
				builtin(context, "offset"),
				builtin(context, "orientation"),
				builtin(context, "pointers"),
				builtin(context, "pointerCount"),
				builtin(context, "powerConnected"),
				builtin(context, "pressure"),
				builtin(context, "proximity"),
				builtin(context, "resolution"),
				builtin(context, "rotationMatrix"),
				builtin(context, "rotationVector"),
				builtin(context, "second"),
				builtin(context, "startRandom"),
				builtin(context, "subsecond"),
				builtin(context, "time"),
				builtin(context, "mediaVolume"),
				builtin(context, "micAmplitude"),
				builtin(context, "touch"),
				builtin(context, "touchStart"));
		filteredUniforms = uniforms;
	}

	@Override
	public Filter getFilter() {
		return filter;
	}

	@Override
	public int getCount() {
		return filteredUniforms.size();
	}

	@Override
	public Uniform getItem(int position) {
		return filteredUniforms.get(position);
	}

	@Override
	public long getItemId(int position) {
		return 0;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		if (convertView == null) {
			convertView = LayoutInflater
					.from(parent.getContext())
					.inflate(R.layout.row_preset, parent, false);
		}

		ViewHolder holder = getViewHolder(convertView);
		Uniform uniform = filteredUniforms.get(position);
		boolean enabled = uniform.isAvailable();

		convertView.setEnabled(enabled);

		holder.name.setTextColor(ContextCompat.getColor(
				parent.getContext(),
				enabled
						? android.R.color.primary_text_dark
						: R.color.disabled_text));
		holder.name.setText(String.format(
				Locale.US,
				uniformFormat,
				uniform.name,
				uniform.type));
		holder.rationale.setText(uniform.rationale);

		return convertView;
	}

	private static Uniform builtin(Context context, String name) {
		UniformDef uniform = UniformTable.get(name);
		return new Uniform(
				uniform.getType().getKeyword(),
				uniform.getDeclarator(),
				context.getString(uniform.getDescription()));
	}

	private ViewHolder getViewHolder(View view) {
		ViewHolder holder;
		if ((holder = (ViewHolder) view.getTag()) == null) {
			holder = new ViewHolder();
			holder.name = view.findViewById(R.id.name);
			holder.rationale = view.findViewById(R.id.rationale);
			view.setTag(holder);
		}

		return holder;
	}

	private static final class ViewHolder {
		private TextView name;
		private TextView rationale;
	}

	private class UniformFilter extends Filter {
		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			String filterString = constraint.toString().toLowerCase(
					Locale.getDefault());
			int size = uniforms.size();
			List<Uniform> filtered = new ArrayList<>(size);

			for (int i = 0; i < size; ++i) {
				Uniform currentUniform = uniforms.get(i);
				if (currentUniform.name.contains(filterString)) {
					filtered.add(currentUniform);
				}
			}

			FilterResults results = new FilterResults();
			results.values = filtered;
			results.count = filtered.size();

			return results;
		}

		@Override
		@SuppressWarnings("unchecked")
		protected void publishResults(CharSequence constraint,
				FilterResults results) {
			filteredUniforms = (ArrayList<Uniform>) results.values;
		}
	}
}
